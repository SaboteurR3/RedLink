package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.command.CommandType;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.missioncontrol.audit.AuditAction;
import ge.tsu.redlink.missioncontrol.audit.AuditLog;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService.Outcome;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService.StoredResponse;
import ge.tsu.redlink.missioncontrol.rover.Rover;
import ge.tsu.redlink.missioncontrol.rover.RoverRepository;
import ge.tsu.redlink.missioncontrol.tracing.LinkTracing;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.UnprocessableException;
import ge.tsu.redlink.missioncontrol.web.EntityTags;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommandService {

    /**
     * Cheapest drive costs 1 % per meter (ground; sand costs more). Earth only rejects what is
     * certainly impossible by its last knowledge; the rover makes the final decision.
     */
    static final double MIN_BATTERY_PER_METER = 1.0;

    /** The rover refuses to drill below this (rover-sim's flight rules); no point sending it. */
    static final double MIN_BATTERY_TO_DRILL = RoverSpecs.DRILL_MIN_BATTERY;

    private final CommandRepository commands;
    private final RoverRepository rovers;
    private final IdempotencyService idempotency;
    private final RoverEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ApplicationEventPublisher ended;
    private final AuditLog audit;
    private final LinkTracing tracing;

    /**
     * Queues a command, at most once per (user, idempotency key). Order matters: the key is looked
     * up before the state checks, so retrying a request that already succeeded replays the success
     * even if the rover's state has changed since.
     *
     * @return the 202 response (fresh or replayed) exactly as it is to be sent
     */
    @Transactional
    public Outcome submit(long roverId, RoverCommand command, String user, String idempotencyKey) {
        Rover rover = findRover(roverId);
        String commandJson = jsonMapper.writeValueAsString(command); // canonical: whitespace and field order don't matter
        String requestHash = IdempotencyService.fingerprint("POST", "/api/v1/rovers/" + roverId + "/commands", commandJson);

        return idempotency.execute(user, idempotencyKey, requestHash, () -> {
            checkAgainstLastKnownState(rover, command);
            Command saved = commands.saveAndFlush(new Command(roverId, command.type(), commandJson, user, now())
                    .tracedAs(tracing.currentTraceparent()));
            log.info("{} queued command {} {} for rover {} ({})", user, saved.getId(), command.type(), roverId, saved.getStatus());
            audit.record(user, AuditAction.COMMAND_SUBMITTED, target(saved), commandJson);
            CommandResponse response = CommandResponse.of(saved, command);
            events.publish(roverId, EventType.COMMAND, response);
            return new StoredResponse(HttpStatus.ACCEPTED.value(), CommandResponse.selfPath(saved.getId()),
                    jsonMapper.writeValueAsString(response));
        });
    }

    @Transactional(readOnly = true)
    public Tagged<CommandResponse> get(long commandId) {
        return tagged(find(commandId));
    }

    /** Newest first; {@code status} filters if given. */
    @Transactional(readOnly = true)
    public Page<CommandResponse> list(long roverId, CommandStatus status, Pageable pageable) {
        findRover(roverId);
        Page<Command> page = status == null
                ? commands.findByRoverId(roverId, pageable)
                : commands.findByRoverIdAndStatus(roverId, status, pageable);
        return page.map(this::toResponse);
    }

    /**
     * Withdraws a command that has not left Earth. Cancelling an already cancelled command
     * succeeds again, so a client can safely retry.
     */
    @Transactional
    public Tagged<CommandResponse> cancel(long commandId, String user) {
        Command command = find(commandId);
        if (command.cancel(user, now())) {
            commands.saveAndFlush(command);
            log.info("{} cancelled command {}", user, commandId);
            audit.record(user, AuditAction.COMMAND_CANCELLED, target(command), null);
            events.publish(command.getRoverId(), EventType.COMMAND, toResponse(command));
            ended.publishEvent(new CommandEnded(command));
        }
        return tagged(command);
    }

    /**
     * A flight director releases a command that waits for approval. It is checked against Earth's
     * latest knowledge again (the rover may have drained or entered safe mode while it waited), and
     * then waits the normal hold, counted from now.
     */
    @Transactional
    public Tagged<CommandResponse> approve(long commandId, String director) {
        Command command = find(commandId);
        if (command.getStatus() == CommandStatus.PENDING_APPROVAL) {
            checkAgainstLastKnownState(findRover(command.getRoverId()),
                    jsonMapper.readValue(command.getCommand(), RoverCommand.class));
        }
        if (command.approve(director, now())) {
            commands.saveAndFlush(command);
            log.info("{} approved command {} of {}", director, commandId, command.getCreatedBy());
            audit.record(director, AuditAction.COMMAND_APPROVED, target(command), "sent by " + command.getCreatedBy());
            events.publish(command.getRoverId(), EventType.COMMAND, toResponse(command));
        }
        return tagged(command);
    }

    /** A flight director stops a command that waits for approval, for good. */
    @Transactional
    public Tagged<CommandResponse> reject(long commandId, String director, String reason) {
        Command command = find(commandId);
        if (command.reject(director, reason, now())) {
            commands.saveAndFlush(command);
            log.info("{} rejected command {} of {}", director, commandId, command.getCreatedBy());
            audit.record(director, AuditAction.COMMAND_REJECTED, target(command), reason);
            events.publish(command.getRoverId(), EventType.COMMAND, toResponse(command));
            ended.publishEvent(new CommandEnded(command));
        }
        return tagged(command);
    }

    private static String target(Command command) {
        return "command:" + command.getId();
    }

    private void checkAgainstLastKnownState(Rover rover, RoverCommand command) {
        if (rover.getLastMode() == RoverMode.SAFE_MODE && !command.type().allowedInSafeMode()) {
            throw new UnprocessableException(ProblemTypes.ROVER_IN_SAFE_MODE, "Rover in safe mode",
                    "Rover " + rover.getId() + " was last reported in safe mode; it only accepts REBOOT");
        }
        if (command.type() == CommandType.DRILL_SAMPLE && rover.getLastBatteryPercent() < MIN_BATTERY_TO_DRILL) {
            throw new UnprocessableException(ProblemTypes.INSUFFICIENT_BATTERY, "Insufficient battery",
                    String.format(Locale.ROOT, "DRILL_SAMPLE needs at least %.0f%% battery, last known level is %.1f%%",
                            MIN_BATTERY_TO_DRILL, rover.getLastBatteryPercent()));
        }
        if (command instanceof RoverCommand.Move move) {
            double needed = move.distanceMeters() * MIN_BATTERY_PER_METER;
            if (rover.getLastBatteryPercent() < needed) {
                throw new UnprocessableException(ProblemTypes.INSUFFICIENT_BATTERY, "Insufficient battery",
                        String.format(Locale.ROOT, "MOVE %d needs at least %.0f%% battery, last known level is %.1f%%",
                                move.distanceMeters(), needed, rover.getLastBatteryPercent()));
            }
        }
    }

    private Tagged<CommandResponse> tagged(Command command) {
        return new Tagged<>(toResponse(command), EntityTags.of(command.getVersion()));
    }

    private CommandResponse toResponse(Command command) {
        return CommandResponse.of(command, jsonMapper);
    }

    private Rover findRover(long roverId) {
        return rovers.findById(roverId).orElseThrow(() ->
                new NotFoundException(ProblemTypes.ROVER_NOT_FOUND, "Rover " + roverId + " does not exist"));
    }

    private Command find(long commandId) {
        return commands.findById(commandId).orElseThrow(() ->
                new NotFoundException(ProblemTypes.COMMAND_NOT_FOUND, "Command " + commandId + " does not exist"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
