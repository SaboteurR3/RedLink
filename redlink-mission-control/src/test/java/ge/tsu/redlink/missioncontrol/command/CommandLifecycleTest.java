package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.command.CommandType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static ge.tsu.redlink.missioncontrol.command.CommandStatus.COMPLETED;
import static ge.tsu.redlink.missioncontrol.command.CommandStatus.EXECUTING;
import static ge.tsu.redlink.missioncontrol.command.CommandStatus.FAILED;
import static ge.tsu.redlink.missioncontrol.command.CommandStatus.RECEIVED;
import static ge.tsu.redlink.missioncontrol.command.CommandStatus.REFUSED_BY_ROVER;
import static ge.tsu.redlink.missioncontrol.command.CommandStatus.TRANSMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telemetry may repeat and reorder: a command's status must only ever move forward.
 */
class CommandLifecycleTest {

    private static final Instant T = Instant.parse("2026-10-26T12:00:00Z");

    private static Command transmitted() {
        Command command = new Command(1, CommandType.MOVE, "{\"type\":\"MOVE\",\"distanceMeters\":3}", "operator", T);
        command.transmit(1, T);
        return command;
    }

    private static boolean report(Command command, CommandStatus status, int progress) {
        return command.applyRoverReport(status, progress, null, null, T.plusSeconds(20), T.plusSeconds(30));
    }

    @Test
    void reportsInOrderMoveTheCommandForward() {
        Command command = transmitted();

        assertThat(report(command, RECEIVED, 0)).isTrue();
        assertThat(report(command, EXECUTING, 33)).isTrue();
        assertThat(report(command, EXECUTING, 66)).isTrue();
        assertThat(report(command, COMPLETED, 100)).isTrue();

        assertThat(command.getStatus()).isEqualTo(COMPLETED);
        assertThat(command.getProgressPercent()).isEqualTo(100);
        assertThat(command.getFinishedAt()).isEqualTo(T.plusSeconds(20)); // Mars time, not arrival time
    }

    @Test
    void lateReportsCannotTurnBackTime() {
        Command command = transmitted();
        report(command, COMPLETED, 100);

        assertThat(report(command, EXECUTING, 50)).isFalse();
        assertThat(report(command, RECEIVED, 0)).isFalse();
        assertThat(command.getStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void progressOnlyRises() {
        Command command = transmitted();
        report(command, EXECUTING, 66);

        assertThat(report(command, EXECUTING, 33)).isFalse();
        assertThat(report(command, EXECUTING, 66)).as("repeated").isFalse();
        assertThat(command.getProgressPercent()).isEqualTo(66);
    }

    @Test
    void reportsMaySkipStates() {
        Command command = transmitted();

        assertThat(report(command, COMPLETED, 100)).isTrue();
        assertThat(command.getStatus()).isEqualTo(COMPLETED);
    }

    @Test
    void failureKeepsTheRoversReasonAndProgress() {
        Command command = transmitted();
        report(command, EXECUTING, 40);

        command.applyRoverReport(FAILED, 40, "OBSTACLE", "Rock at (32,27)", T.plusSeconds(21), T.plusSeconds(31));

        assertThat(command.getStatus()).isEqualTo(FAILED);
        assertThat(command.getFailureCode()).isEqualTo("OBSTACLE");
        assertThat(command.getFailureDetail()).isEqualTo("Rock at (32,27)");
        assertThat(command.getProgressPercent()).isEqualTo(40);
    }

    @Test
    void finalStatesAreFinal() {
        Command command = transmitted();
        report(command, REFUSED_BY_ROVER, 0);

        assertThat(report(command, COMPLETED, 100)).isFalse();
        assertThat(command.getStatus()).isEqualTo(REFUSED_BY_ROVER);
    }

    @Test
    void reportsAboutCommandsThatNeverLeftEarthAreIgnored() {
        Command queued = new Command(1, CommandType.MOVE, "{}", "operator", T);

        assertThat(report(queued, RECEIVED, 0)).isFalse();
        assertThat(queued.getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(TRANSMITTED.lifecycleRank()).isLessThan(RECEIVED.lifecycleRank());
    }
}
