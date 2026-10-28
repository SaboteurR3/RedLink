package ge.tsu.redlink.contract.rover;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;

/**
 * The rover's published spec sheet: what each action costs and where its limits are. The rover's
 * flight software executes by these numbers and Earth plans missions with them, so both sides
 * always agree on what is possible.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RoverSpecs {

    /** Steepest slope the rover may drive (Curiosity's rated limit), in degrees. */
    public static final double MAX_SLOPE_DEGREES = 30;

    /** Battery percent per meter on ground. */
    public static final double MOVE_COST_GROUND = 1.0;
    /** Battery percent per meter in sand. */
    public static final double MOVE_COST_SAND = 2.0;
    /** Battery percent per 90° turn. */
    public static final double ROTATE_COST = 0.2;

    public static final double PHOTO_COST = 0.5;
    /** Storage percent one photo takes. */
    public static final int PHOTO_STORAGE = 2;

    public static final double DRILL_COST = 8;
    /** The rover refuses to drill below this battery level. */
    public static final double DRILL_MIN_BATTERY = 30;
    /** Storage percent one sample takes. */
    public static final int DRILL_STORAGE = 5;

    /** Battery percent gained per minute of charging. */
    public static final double CHARGE_PER_MINUTE = 1.0;

    /** During a dust storm the solar panels deliver only this fraction of {@link #CHARGE_PER_MINUTE}. */
    public static final double DUST_STORM_CHARGE_FACTOR = 0.25;

    // How long each step takes on Mars (charging time per minute is a simulation setting of the rover).
    public static final Duration MOVE_STEP = Duration.ofSeconds(1);
    public static final Duration ROTATE_STEP = Duration.ofSeconds(2);
    public static final Duration PHOTO_TIME = Duration.ofSeconds(3);
    public static final Duration DRILL_TIME = Duration.ofSeconds(15);
    public static final Duration REBOOT_TIME = Duration.ofSeconds(5);
}
