package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.missioncontrol.command.Command;
import ge.tsu.redlink.missioncontrol.command.CommandStatus;

import java.util.Collection;

/**
 * A mission's status, derived from its steps (the single source of truth), in this order of
 * precedence.
 */
public enum MissionStatus {
    /** A step failed, was refused by the rover or rejected by a flight director. */
    FAILED,
    /** A step was cancelled (by an operator, or because the mission was cancelled). */
    CANCELLED,
    /** Every step completed. */
    COMPLETED,
    /** At least one step has left Earth. */
    IN_PROGRESS,
    /** Nothing has left Earth yet (steps may wait for the hold or for approval). */
    QUEUED;

    static MissionStatus of(Collection<Command> steps) {
        if (steps.stream().anyMatch(step -> step.getStatus() == CommandStatus.FAILED
                || step.getStatus() == CommandStatus.REFUSED_BY_ROVER || step.getStatus() == CommandStatus.REJECTED)) {
            return FAILED;
        }
        if (steps.stream().anyMatch(step -> step.getStatus() == CommandStatus.CANCELLED)) {
            return CANCELLED;
        }
        if (steps.stream().allMatch(step -> step.getStatus() == CommandStatus.COMPLETED)) {
            return COMPLETED;
        }
        if (steps.stream().anyMatch(step -> !step.getStatus().isCancellable())) {
            return IN_PROGRESS;
        }
        return QUEUED;
    }
}
