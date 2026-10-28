package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.missioncontrol.command.Command;
import ge.tsu.redlink.missioncontrol.command.CommandEnded;
import ge.tsu.redlink.missioncontrol.command.CommandRepository;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * When a mission step ends badly, the later steps still on Earth are withdrawn: they were planned
 * on the assumption that it succeeds (a drill after a failed drive would drill in the wrong place).
 * Runs inside the transaction that ended the step, so both commit together. Steps that already
 * left Earth cannot be called back; the rover's own checks and safe mode take care of them.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class MissionAborter {

    private final CommandRepository commands;
    private final RoverEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @EventListener
    void onCommandEnded(CommandEnded event) {
        Command ended = event.command();
        if (ended.getMissionId() == null || !ended.endedUnsuccessfully()) {
            return;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Command> aborted = commands.findByMissionIdInOrderByMissionIdAscMissionStepAsc(List.of(ended.getMissionId()))
                .stream()
                .filter(step -> step.getMissionStep() > ended.getMissionStep())
                .filter(step -> step.abortWithMission(ended.getMissionStep(), now))
                .toList();
        if (!aborted.isEmpty()) {
            commands.flush();
            aborted.forEach(step -> events.publish(step.getRoverId(), EventType.COMMAND, CommandResponse.of(step, jsonMapper)));
            log.info("Mission {} aborted after step {} ended {}: {} later steps withdrawn", ended.getMissionId(),
                    ended.getMissionStep(), ended.getStatus(), aborted.size());
        }
    }
}
