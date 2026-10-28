package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.missioncontrol.command.Command;
import ge.tsu.redlink.missioncontrol.command.CommandRepository;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.command.CommandStatus;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService.Outcome;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService.StoredResponse;
import ge.tsu.redlink.missioncontrol.mission.MissionExceptions.MissionNotCancellableException;
import ge.tsu.redlink.missioncontrol.mission.MissionExceptions.MissionNotFoundException;
import ge.tsu.redlink.missioncontrol.mission.MissionExceptions.PlanInfeasibleException;
import ge.tsu.redlink.missioncontrol.mission.MissionExceptions.RoverBusyException;
import ge.tsu.redlink.missioncontrol.rover.Rover;
import ge.tsu.redlink.missioncontrol.rover.RoverRepository;
import ge.tsu.redlink.missioncontrol.site.SiteService;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MissionService {

    /** Commands Earth has not heard the end of yet. */
    private static final Set<CommandStatus> UNFINISHED = EnumSet.of(CommandStatus.PENDING_APPROVAL,
            CommandStatus.QUEUED, CommandStatus.TRANSMITTED, CommandStatus.RECEIVED, CommandStatus.EXECUTING);

    private final MissionRepository missions;
    private final CommandRepository commands;
    private final RoverRepository rovers;
    private final SiteService sites;
    private final IdempotencyService idempotency;
    private final RoverEventPublisher events;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Checks the whole plan and queues all its steps, at most once per (user, idempotency key).
     *
     * @throws RoverBusyException      if the rover still has unfinished commands
     * @throws PlanInfeasibleException naming the first step the rover could not do
     */
    @Transactional
    public Outcome submit(long roverId, MissionRequest request, String user, String idempotencyKey) {
        findRover(roverId);
        String planJson = jsonMapper.writeValueAsString(request); // canonical form of the plan
        String requestHash = IdempotencyService.fingerprint("POST", "/api/v1/rovers/" + roverId + "/missions", planJson);

        return idempotency.execute(user, idempotencyKey, requestHash, () -> {
            // Serialises mission submissions per rover, so two plans cannot both pass the "idle" check.
            jdbc.sql("SELECT id FROM rovers WHERE id = ? FOR UPDATE").param(roverId).query(Long.class).single();
            if (commands.existsByRoverIdAndStatusIn(roverId, UNFINISHED)) {
                throw new RoverBusyException(roverId);
            }
            Rover rover = findRover(roverId);
            MissionPlanner.State start = new MissionPlanner.State(rover.getLastX(), rover.getLastY(),
                    rover.getLastHeading(), rover.getLastBatteryPercent(), rover.getLastStorageUsedPercent(),
                    rover.getLastMode());
            MissionPlanner.Result result = MissionPlanner.plan(start, request.steps(),
                    sites.get(rover.getSiteId()).terrain());
            if (result instanceof MissionPlanner.Result.Infeasible infeasible) {
                throw new PlanInfeasibleException(infeasible.step() + 1, infeasible.command().type(), infeasible.reason());
            }
            MissionResponse.Forecast forecast = MissionResponse.Forecast.of(((MissionPlanner.Result.Feasible) result).end());

            Instant now = now();
            Mission mission = missions.saveAndFlush(new Mission(roverId, request.name().strip(), user, now,
                    jsonMapper.writeValueAsString(forecast)));
            List<Command> steps = new ArrayList<>();
            for (int i = 0; i < request.steps().size(); i++) {
                RoverCommand step = request.steps().get(i);
                steps.add(new Command(roverId, step.type(), jsonMapper.writeValueAsString(step), user, now,
                        mission.getId(), i + 1));
            }
            // Saved in order, so ids (and with them uplink order) follow the plan.
            steps = commands.saveAllAndFlush(steps);
            log.info("{} planned mission {} '{}' for rover {} ({} steps)", user, mission.getId(), mission.getName(),
                    roverId, steps.size());
            MissionResponse response = toResponse(mission, steps);
            response.steps().forEach(step -> events.publish(roverId, EventType.COMMAND, step));
            return new StoredResponse(HttpStatus.ACCEPTED.value(), MissionResponse.selfPath(mission.getId()),
                    jsonMapper.writeValueAsString(response));
        });
    }

    @Transactional(readOnly = true)
    public Tagged<MissionResponse> get(long missionId) {
        Mission mission = find(missionId);
        return tagged(mission, stepsOf(List.of(missionId)).getOrDefault(missionId, List.of()));
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public Page<MissionResponse> list(long roverId, Pageable pageable) {
        findRover(roverId);
        Page<Mission> page = missions.findByRoverId(roverId, pageable);
        Map<Long, List<Command>> steps = stepsOf(page.map(Mission::getId).getContent());
        return page.map(mission -> toResponse(mission, steps.getOrDefault(mission.getId(), List.of())));
    }

    /**
     * Withdraws every step still on Earth. Repeating it after it worked succeeds again.
     *
     * @throws MissionNotCancellableException if nothing is left on Earth and nothing was ever cancelled
     */
    @Transactional
    public Tagged<MissionResponse> cancel(long missionId, String user) {
        Mission mission = find(missionId);
        List<Command> steps = stepsOf(List.of(missionId)).getOrDefault(missionId, List.of());
        List<Command> onEarth = steps.stream().filter(step -> step.getStatus().isCancellable()).toList();
        if (onEarth.isEmpty()) {
            if (steps.stream().anyMatch(step -> step.getStatus() == CommandStatus.CANCELLED)) {
                return tagged(mission, steps);
            }
            throw new MissionNotCancellableException(missionId, MissionStatus.of(steps));
        }
        Instant now = now();
        for (Command step : onEarth) {
            step.cancel(user, now);
        }
        commands.flush(); // version checks now: a concurrent transmission makes this fail with 412, not later
        onEarth.forEach(step -> events.publish(mission.getRoverId(), EventType.COMMAND, CommandResponse.of(step, jsonMapper)));
        log.info("{} cancelled mission {} ({} steps withdrawn)", user, missionId, onEarth.size());
        return tagged(mission, steps);
    }

    private Tagged<MissionResponse> tagged(Mission mission, List<Command> steps) {
        // Versions only grow, so the sum changes whenever any step changes.
        long versions = mission.getVersion() + steps.stream().mapToLong(Command::getVersion).sum();
        return new Tagged<>(toResponse(mission, steps), "\"" + versions + "\"");
    }

    private MissionResponse toResponse(Mission mission, List<Command> steps) {
        Map<String, CommandResponse.Link> links = new LinkedHashMap<>();
        links.put("self", new CommandResponse.Link(MissionResponse.selfPath(mission.getId()), null));
        links.put("rover", new CommandResponse.Link("/api/v1/rovers/" + mission.getRoverId(), null));
        if (steps.stream().anyMatch(step -> step.getStatus().isCancellable())) {
            links.put("cancel", new CommandResponse.Link(MissionResponse.selfPath(mission.getId()) + "/cancel", "POST"));
        }
        int completed = (int) steps.stream().filter(step -> step.getStatus() == CommandStatus.COMPLETED).count();
        return new MissionResponse(mission.getId(), mission.getRoverId(), mission.getName(), MissionStatus.of(steps),
                new MissionResponse.Progress(completed, steps.size()),
                jsonMapper.readValue(mission.getForecast(), MissionResponse.Forecast.class),
                mission.getCreatedBy(), mission.getCreatedAt(),
                steps.stream().map(step -> CommandResponse.of(step, jsonMapper)).toList(), links);
    }

    private Map<Long, List<Command>> stepsOf(List<Long> missionIds) {
        if (missionIds.isEmpty()) {
            return Map.of();
        }
        return commands.findByMissionIdInOrderByMissionIdAscMissionStepAsc(missionIds).stream()
                .collect(Collectors.groupingBy(Command::getMissionId, Collectors.toList()));
    }

    private Mission find(long missionId) {
        return missions.findById(missionId).orElseThrow(() -> new MissionNotFoundException(missionId));
    }

    private Rover findRover(long roverId) {
        return rovers.findById(roverId).orElseThrow(() ->
                new NotFoundException(ProblemTypes.ROVER_NOT_FOUND, "Rover " + roverId + " does not exist"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
