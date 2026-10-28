package ge.tsu.redlink.missioncontrol.command;

/**
 * Where a command that needs approval stands.
 */
public enum ApprovalState {
    /** Waiting for a flight director. */
    PENDING,
    APPROVED,
    REJECTED,
    /** Cancelled by an operator before anyone decided. */
    WITHDRAWN
}
