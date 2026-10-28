package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.command.CommandType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalRulesTest {

    private static final Instant CREATED = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant LATER = CREATED.plusSeconds(3600);

    private static Command drill() {
        return new Command(1, CommandType.DRILL_SAMPLE, "{\"type\":\"DRILL_SAMPLE\"}", "operator", CREATED);
    }

    @Test
    void drillWaitsForApprovalAndIsNotQueuedYet() {
        Command drill = drill();

        assertThat(drill.getStatus()).isEqualTo(CommandStatus.PENDING_APPROVAL);
        assertThat(drill.approvalState()).isEqualTo(ApprovalState.PENDING);
        assertThat(drill.getQueuedAt()).isNull();
    }

    @Test
    void approvalQueuesItAndStartsTheHoldNow() {
        Command drill = drill();

        assertThat(drill.approve("director", LATER)).isTrue();

        assertThat(drill.getStatus()).isEqualTo(CommandStatus.QUEUED);
        assertThat(drill.getQueuedAt()).isEqualTo(LATER); // not CREATED: the cancel window is never skipped
        assertThat(drill.approvalState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(drill.getDecidedBy()).isEqualTo("director");
    }

    @Test
    void nobodyApprovesTheirOwnCommand() {
        Command drill = drill();

        assertThatThrownBy(() -> drill.approve("operator", LATER)).isInstanceOf(FourEyesViolationException.class);
        assertThat(drill.getStatus()).isEqualTo(CommandStatus.PENDING_APPROVAL);
    }

    @Test
    void rejectionIsFinalAndKeepsTheReason() {
        Command drill = drill();

        assertThat(drill.reject("director", "Battery margin too thin", LATER)).isTrue();

        assertThat(drill.getStatus()).isEqualTo(CommandStatus.REJECTED);
        assertThat(drill.getStatus().isFinal()).isTrue();
        assertThat(drill.getDecisionReason()).isEqualTo("Battery margin too thin");
        assertThat(drill.approvalState()).isEqualTo(ApprovalState.REJECTED);
    }

    @Test
    void repeatingADecisionIsHarmless() {
        Command approved = drill();
        approved.approve("director", LATER);
        Command rejected = drill();
        rejected.reject("director", null, LATER);

        assertThat(approved.approve("director", LATER.plusSeconds(1))).isFalse();
        assertThat(rejected.reject("director", null, LATER.plusSeconds(1))).isFalse();
    }

    @Test
    void contradictingADecisionIsAConflict() {
        Command approved = drill();
        approved.approve("director", LATER);
        Command cancelled = drill();
        cancelled.cancel("operator", LATER);

        assertThatThrownBy(() -> approved.reject("director", null, LATER)).isInstanceOf(CommandNotPendingException.class);
        assertThatThrownBy(() -> cancelled.approve("director", LATER)).isInstanceOf(CommandNotPendingException.class);
        assertThat(cancelled.approvalState()).isEqualTo(ApprovalState.WITHDRAWN);
    }

    @Test
    void commandsThatNeedNoApprovalCannotBeApproved() {
        Command move = new Command(1, CommandType.MOVE, "{}", "operator", CREATED);

        assertThat(move.approvalState()).isNull();
        assertThat(move.getQueuedAt()).isEqualTo(CREATED);
        assertThatThrownBy(() -> move.approve("director", LATER)).isInstanceOf(CommandNotPendingException.class);
    }
}
