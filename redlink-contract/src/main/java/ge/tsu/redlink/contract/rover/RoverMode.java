package ge.tsu.redlink.contract.rover;

public enum RoverMode {
    /** Executes commands normally. */
    NOMINAL,
    /**
     * Entered after a fault. The rover stops and refuses every command except
     * {@link ge.tsu.redlink.contract.command.CommandType#REBOOT}.
     */
    SAFE_MODE
}