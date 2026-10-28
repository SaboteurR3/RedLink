package ge.tsu.redlink.missioncontrol.audit;

/** The changes that are recorded in the audit log. */
public enum AuditAction {
    COMMAND_SUBMITTED,
    COMMAND_CANCELLED,
    COMMAND_APPROVED,
    COMMAND_REJECTED,
    MISSION_SUBMITTED,
    MISSION_CANCELLED,
    CONJUNCTION_DECLARED,
    CONJUNCTION_ENDED,
    WEBHOOK_REGISTERED,
    WEBHOOK_DELETED
}
