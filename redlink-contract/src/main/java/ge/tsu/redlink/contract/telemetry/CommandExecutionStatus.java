package ge.tsu.redlink.contract.telemetry;

/**
 * Command progress as seen by the rover. Earth maps these onto its own, larger command lifecycle.
 */
public enum CommandExecutionStatus {
    RECEIVED,
    EXECUTING,
    COMPLETED,
    FAILED,
    /** Not executed at all: invalid, or the rover is in safe mode. */
    REFUSED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == REFUSED;
    }
}
