package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;

/**
 * A command's lifecycle as Earth sees it. Everything after {@link #TRANSMITTED} is learned only
 * from (delayed) telemetry.
 *
 * <pre>
 * PENDING_APPROVAL ─approve─► QUEUED ─uplink─► TRANSMITTED ─telemetry─► RECEIVED ─► EXECUTING ─► COMPLETED / FAILED
 *        │ reject                │ cancel                     └──────────────────────────────────► REFUSED_BY_ROVER
 *        ▼                       ▼
 *     REJECTED               CANCELLED
 * </pre>
 */
public enum CommandStatus {
    PENDING_APPROVAL,
    QUEUED,
    TRANSMITTED,
    RECEIVED,
    EXECUTING,
    COMPLETED,
    FAILED,
    REFUSED_BY_ROVER,
    CANCELLED,
    REJECTED;

    /** Still on Earth: nothing has been sent, so it can be withdrawn. */
    public boolean isCancellable() {
        return this == PENDING_APPROVAL || this == QUEUED;
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == REFUSED_BY_ROVER || this == CANCELLED || this == REJECTED;
    }

    /**
     * Position in the forward-only lifecycle once the command has left Earth:
     * TRANSMITTED &lt; RECEIVED &lt; EXECUTING &lt; final.
     */
    int lifecycleRank() {
        return switch (this) {
            case PENDING_APPROVAL, QUEUED -> 0;
            case TRANSMITTED -> 1;
            case RECEIVED -> 2;
            case EXECUTING -> 3;
            case COMPLETED, FAILED, REFUSED_BY_ROVER, CANCELLED, REJECTED -> 4;
        };
    }

    /** Earth's name for a status the rover reports. */
    public static CommandStatus fromRover(CommandExecutionStatus reported) {
        return switch (reported) {
            case RECEIVED -> RECEIVED;
            case EXECUTING -> EXECUTING;
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case REFUSED -> REFUSED_BY_ROVER;
        };
    }
}
