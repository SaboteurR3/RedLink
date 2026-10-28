package ge.tsu.redlink.missioncontrol.user;

/**
 * Operator roles, lowest to highest. Each role includes every role below it (see the role
 * hierarchy in {@code SecurityConfig}).
 */
public enum Role {
    /** Watches rovers and telemetry. */
    VIEWER,
    /** Sends commands. */
    OPERATOR,
    /** Approves risky commands and controls the link. */
    FLIGHT_DIRECTOR
}
