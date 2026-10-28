package ge.tsu.redlink.contract.telemetry;

/**
 * The telemetry dictionary: every kind of report the rover sends to Earth. Each constant has
 * exactly one {@link TelemetryPayload} record with the same name as its JSON type id.
 */
public enum TelemetryType {
    /** Progress of one command: received, executing, completed, failed or refused. */
    COMMAND_STATUS,
    /** Snapshot of position, power, thermal and mode. */
    ROVER_STATE,
    /** Something noteworthy happened that is not tied to a command (dust storm, fault). */
    EVENT,
    /** An image taken by TAKE_PHOTO, as a base64 PNG. */
    PHOTO,
    /** A sample taken by DRILL_SAMPLE: rock or soil, and where. */
    SAMPLE
}
