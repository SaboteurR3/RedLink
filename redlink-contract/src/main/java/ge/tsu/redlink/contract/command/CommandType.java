package ge.tsu.redlink.contract.command;

import lombok.RequiredArgsConstructor;

/**
 * The command dictionary: every command the rover understands. Each constant has exactly one
 * {@link RoverCommand} record with the same name as its JSON type id.
 */
@RequiredArgsConstructor
public enum CommandType {
    MOVE(false, false),
    ROTATE(false, false),
    TAKE_PHOTO(false, false),
    /** Drilling wears the bit and can damage the rover, so a flight director must approve it. */
    DRILL_SAMPLE(true, false),
    CHARGE(false, false),
    /** The only command a rover in safe mode accepts. */
    REBOOT(false, true);

    private final boolean requiresApproval;
    private final boolean allowedInSafeMode;

    public boolean requiresApproval() {
        return requiresApproval;
    }

    public boolean allowedInSafeMode() {
        return allowedInSafeMode;
    }
}
