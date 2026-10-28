package ge.tsu.redlink.roversim.flight;

import lombok.RequiredArgsConstructor;

/**
 * Why a command failed or was refused. Sent to Earth as {@code failureCode} in COMMAND_STATUS.
 */
@RequiredArgsConstructor
public enum FailureCode {
    /** Rock or map edge in the way. */
    OBSTACLE(true),
    /** The next cell is too steep to drive onto. */
    SLOPE_TOO_STEEP(true),
    /** Not enough battery left for the next step. */
    BATTERY_EXHAUSTED(true),
    STORAGE_FULL(false),
    /** Below the minimum battery level this command needs to start. */
    LOW_BATTERY(false),
    /** A wheel got stuck in the regolith; the drive stopped, but nothing is damaged: drive again. */
    WHEEL_STUCK(false),

    // Refusals: the command never started.
    /** The rover is in safe mode and accepts only REBOOT. */
    SAFE_MODE(false),
    /** Arrived after the rover had given up waiting for it and moved past its sequence number. */
    SEQUENCE_EXPIRED(false);

    private final boolean entersSafeMode;

    /** Hazards stop the rover until Earth has looked at the situation and sent REBOOT. */
    public boolean entersSafeMode() {
        return entersSafeMode;
    }
}
