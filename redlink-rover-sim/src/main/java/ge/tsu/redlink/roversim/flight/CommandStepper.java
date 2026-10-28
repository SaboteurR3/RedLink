package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.command.CommandType;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.EventSeverity;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.roversim.camera.PhotoRenderer;
import ge.tsu.redlink.roversim.downlink.TelemetryOutbox;
import ge.tsu.redlink.roversim.events.Dice;
import ge.tsu.redlink.roversim.events.RoverEventsProperties;
import ge.tsu.redlink.roversim.rover.RoverState;
import ge.tsu.redlink.roversim.rover.RoverStateRepository;
import ge.tsu.redlink.roversim.tracing.LinkTracing;
import ge.tsu.redlink.roversim.uplink.ReceivedCommand;
import ge.tsu.redlink.roversim.uplink.ReceivedCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * The flight software's main loop body: one call does at most one thing for one rover — start the
 * next command, or perform the current command's next step once it is due — and reports it to
 * Earth, all in one transaction.
 */
@Service
@EnableConfigurationProperties(RoverProperties.class)
@Slf4j
@RequiredArgsConstructor
public class CommandStepper {

    private final RoverStateRepository rovers;
    private final ReceivedCommandRepository commands;
    private final TelemetryOutbox telemetry;
    private final TerrainRegistry terrains;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RoverProperties properties;
    private final RoverEventsProperties events;
    private final Dice dice;
    private final LinkTracing tracing;

    @Transactional
    public void advance(long roverId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RoverState rover = rovers.findById(roverId)
                .orElseThrow(() -> new IllegalArgumentException("No rover " + roverId));

        // Work on a command continues Earth's trace of it (from the packet); idle ticks make no spans.
        Optional<ReceivedCommand> executing = commands.findExecuting(roverId);
        if (executing.isPresent()) {
            ReceivedCommand current = executing.get();
            if (!now.isBefore(current.getNextStepAt())) {
                tracing.inSpan(current.getTraceParent(), "rover step " + current.getType(),
                        () -> continueCommand(rover, current, now));
            }
        } else {
            commands.findNextWaiting(roverId).ifPresent(next -> tracing.inSpan(next.getTraceParent(),
                    "rover start " + next.getType(), () -> startNext(rover, next, now)));
        }
    }

    private void startNext(RoverState rover, ReceivedCommand next, Instant now) {
        long expected = rover.getLastSequenceNo() + 1;
        if (next.getSequenceNo() < expected) {
            refuse(rover, next, FailureCode.SEQUENCE_EXPIRED, "Sequence " + next.getSequenceNo()
                    + " arrived after the rover had moved on to " + rover.getLastSequenceNo(), now);
            return;
        }
        if (next.getSequenceNo() > expected) {
            if (now.isBefore(next.getReceivedAt().plus(properties.reorderTimeout()))) {
                return; // a lower sequence number may still be on its way
            }
            String missing = expected == next.getSequenceNo() - 1
                    ? String.valueOf(expected) : expected + "–" + (next.getSequenceNo() - 1);
            telemetry.emit(rover.getRoverId(), new TelemetryPayload.Event("SEQUENCE_GAP_SKIPPED", EventSeverity.WARNING,
                    "Gave up waiting for sequence " + missing + " after " + properties.reorderTimeout().toSeconds() + " s"));
            log.warn("Rover {} skipped missing sequence {}", rover.getRoverId(), missing);
        }

        rover.advanceSequence(next.getSequenceNo(), now);
        RoverCommand command = commandOf(next);
        if (rover.getMode() == RoverMode.SAFE_MODE && !command.type().allowedInSafeMode()) {
            refuse(rover, next, FailureCode.SAFE_MODE, "Rover is in safe mode; only REBOOT is accepted", now);
            return;
        }

        FlightRules.Plan plan = FlightRules.plan(command, rover.snapshot(), properties.chargeMinute());
        next.start(plan.totalSteps(), now.plus(plan.stepDuration()), now);
        reportStatus(next, CommandExecutionStatus.EXECUTING);
        if (next.allStepsDone()) {
            next.complete(now);
            reportStatus(next, CommandExecutionStatus.COMPLETED);
        }
    }

    private void continueCommand(RoverState rover, ReceivedCommand current, Instant now) {
        if (now.isBefore(current.getNextStepAt())) {
            return;
        }
        RoverCommand command = commandOf(current);
        // Random events are decided here, so FlightRules stays pure.
        boolean wheelStuck = command.type() == CommandType.MOVE && events.enabled()
                && dice.chance(events.wheelStuckChancePerMeter());
        FlightRules.Conditions conditions = new FlightRules.Conditions(rover.inDustStorm(now), wheelStuck);
        StepOutcome outcome = FlightRules.step(command, rover.snapshot(), terrains.get(rover.getSiteId()), conditions);

        if (outcome instanceof StepOutcome.Failed failed) {
            current.fail(failed.code().name(), failed.detail(), now);
            reportStatus(current, CommandExecutionStatus.FAILED);
            if (failed.code().entersSafeMode()) {
                rover.apply(failed.rover().inMode(RoverMode.SAFE_MODE), now);
                telemetry.emit(rover.getRoverId(), new TelemetryPayload.Event("SAFE_MODE_ENTERED", EventSeverity.CRITICAL,
                        failed.detail() + ". Awaiting REBOOT."));
                telemetry.emit(rover.getRoverId(), rover.snapshot().toTelemetry());
                log.warn("Rover {} entered safe mode: {}", rover.getRoverId(), failed.detail());
            }
            return;
        }

        rover.apply(outcome.rover(), now);
        telemetry.emit(rover.getRoverId(), rover.snapshot().toTelemetry());
        if (command instanceof RoverCommand.TakePhoto takePhoto) {
            telemetry.emit(rover.getRoverId(), photo(rover, current, takePhoto));
        }
        if (command.type() == CommandType.DRILL_SAMPLE) {
            telemetry.emit(rover.getRoverId(), new TelemetryPayload.Sample(current.getCommandId(),
                    FlightRules.sampleMaterial(rover.snapshot(), terrains.get(rover.getSiteId())),
                    rover.getX(), rover.getY(), rover.getHeading()));
        }
        // Keep the original cadence: a 5 m drive takes 5 s, however the ticks fall.
        current.stepDone(current.getNextStepAt().plus(
                FlightRules.plan(command, rover.snapshot(), properties.chargeMinute()).stepDuration()));
        if (current.allStepsDone()) {
            current.complete(now);
            reportStatus(current, CommandExecutionStatus.COMPLETED);
        } else {
            reportStatus(current, CommandExecutionStatus.EXECUTING);
        }
    }

    /** Rendered from where the rover stands now, in the same transaction as the step that took it. */
    private TelemetryPayload.Photo photo(RoverState rover, ReceivedCommand command, RoverCommand.TakePhoto takePhoto) {
        byte[] png = PhotoRenderer.render(terrains.get(rover.getSiteId()), rover.getX(), rover.getY(),
                rover.getHeading(), takePhoto.camera());
        return new TelemetryPayload.Photo(command.getCommandId(), takePhoto.camera(), rover.getX(), rover.getY(),
                rover.getHeading(), Base64.getEncoder().encodeToString(png));
    }

    private void refuse(RoverState rover, ReceivedCommand command, FailureCode code, String detail, Instant now) {
        command.refuse(code.name(), detail, now);
        reportStatus(command, CommandExecutionStatus.REFUSED);
        log.info("Rover {} refused command {}: {}", rover.getRoverId(), command.getCommandId(), detail);
    }

    private void reportStatus(ReceivedCommand command, CommandExecutionStatus status) {
        telemetry.emit(command.getRoverId(), new TelemetryPayload.CommandStatus(command.getCommandId(), status,
                command.getProgressPercent(), command.getFailureCode(), command.getFailureDetail()));
    }

    private RoverCommand commandOf(ReceivedCommand command) {
        return jsonMapper.readValue(command.getCommand(), RoverCommand.class);
    }
}
