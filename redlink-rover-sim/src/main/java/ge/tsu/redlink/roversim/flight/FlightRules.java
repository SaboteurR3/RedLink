package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.SampleMaterial;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Locale;

/**
 * The rover's physics and fault rules, without any persistence: every command is a number of
 * steps of fixed duration, and each step turns one {@link RoverSnapshot} into the next.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FlightRules {

    // The numbers come from the published spec sheet, which Earth also plans with.
    public static final double MAX_SLOPE_DEGREES = RoverSpecs.MAX_SLOPE_DEGREES;

    static final double MOVE_COST_GROUND = RoverSpecs.MOVE_COST_GROUND;
    static final double MOVE_COST_SAND = RoverSpecs.MOVE_COST_SAND;
    static final double ROTATE_COST = RoverSpecs.ROTATE_COST;
    static final double PHOTO_COST = RoverSpecs.PHOTO_COST;
    static final int PHOTO_STORAGE = RoverSpecs.PHOTO_STORAGE;
    static final double DRILL_COST = RoverSpecs.DRILL_COST;
    static final double DRILL_MIN_BATTERY = RoverSpecs.DRILL_MIN_BATTERY;
    static final int DRILL_STORAGE = RoverSpecs.DRILL_STORAGE;
    static final double CHARGE_PER_MINUTE = RoverSpecs.CHARGE_PER_MINUTE;

    /**
     * How long a command takes: number of steps and the time of each.
     *
     * @param chargeMinute how long one simulated charging minute lasts
     */
    public static Plan plan(RoverCommand command, RoverSnapshot rover, Duration chargeMinute) {
        // Java 17 has no pattern matching for switch; switching on the type enum keeps the
        // compiler's exhaustiveness check (a new CommandType is a compile error here).
        return switch (command.type()) {
            case MOVE -> new Plan(((RoverCommand.Move) command).distanceMeters(), RoverSpecs.MOVE_STEP);
            case ROTATE -> new Plan(Math.abs(quarterTurns(rover.heading(), ((RoverCommand.Rotate) command).heading())),
                    RoverSpecs.ROTATE_STEP);
            case TAKE_PHOTO -> new Plan(1, RoverSpecs.PHOTO_TIME);
            case DRILL_SAMPLE -> new Plan(1, RoverSpecs.DRILL_TIME);
            case CHARGE -> new Plan(((RoverCommand.Charge) command).minutes(), chargeMinute);
            case REBOOT -> new Plan(1, RoverSpecs.REBOOT_TIME);
        };
    }

    /**
     * Performs the next step of {@code command}. Checks happen before anything changes, so a failed
     * step leaves the rover exactly as it was.
     */
    public static StepOutcome step(RoverCommand command, RoverSnapshot rover, Terrain terrain) {
        return step(command, rover, terrain, Conditions.NOMINAL);
    }

    /**
     * The same, under the given conditions (random events decided by the caller, so this stays pure).
     */
    public static StepOutcome step(RoverCommand command, RoverSnapshot rover, Terrain terrain, Conditions conditions) {
        return switch (command.type()) {
            case MOVE -> conditions.wheelStuck()
                    ? new StepOutcome.Failed(FailureCode.WHEEL_STUCK, "A wheel got stuck at (" + rover.x() + "," + rover.y()
                    + "); the drive stopped. Send the MOVE again", rover)
                    : moveOneMeter(rover, terrain);
            case ROTATE -> turnTowards(rover, ((RoverCommand.Rotate) command).heading());
            case TAKE_PHOTO -> store(rover, PHOTO_STORAGE, PHOTO_COST);
            case DRILL_SAMPLE -> rover.batteryPercent() < DRILL_MIN_BATTERY
                    ? new StepOutcome.Failed(FailureCode.LOW_BATTERY, String.format(Locale.ROOT,
                    "Drilling needs %.0f%% battery, rover has %.2f%%", DRILL_MIN_BATTERY, rover.batteryPercent()), rover)
                    : store(rover, DRILL_STORAGE, DRILL_COST);
            case CHARGE -> new StepOutcome.Advanced(rover.charged(conditions.dustStorm()
                    ? CHARGE_PER_MINUTE * RoverSpecs.DUST_STORM_CHARGE_FACTOR : CHARGE_PER_MINUTE));
            case REBOOT -> new StepOutcome.Advanced(rover.inMode(RoverMode.NOMINAL));
        };
    }

    /**
     * What a drill at the rover's position takes: the rock in the cell it faces if there is one,
     * otherwise the soil just in front of its wheels. The rock stays; only a core is taken.
     */
    public static SampleMaterial sampleMaterial(RoverSnapshot rover, Terrain terrain) {
        int aheadX = rover.x() + dx(rover.heading());
        int aheadY = rover.y() + dy(rover.heading());
        return terrain.contains(aheadX, aheadY) && terrain.isBlocked(aheadX, aheadY) ? SampleMaterial.ROCK : SampleMaterial.SOIL;
    }

    private static StepOutcome moveOneMeter(RoverSnapshot rover, Terrain terrain) {
        int toX = rover.x() + dx(rover.heading());
        int toY = rover.y() + dy(rover.heading());
        if (!terrain.contains(toX, toY)) {
            return new StepOutcome.Failed(FailureCode.OBSTACLE, "Edge of the map at (" + toX + "," + toY + ")", rover);
        }
        if (terrain.isBlocked(toX, toY)) {
            return new StepOutcome.Failed(FailureCode.OBSTACLE, "Rock at (" + toX + "," + toY + ")", rover);
        }
        double slope = terrain.slopeDegrees(rover.x(), rover.y(), toX, toY);
        if (slope > MAX_SLOPE_DEGREES) {
            return new StepOutcome.Failed(FailureCode.SLOPE_TOO_STEEP, String.format(Locale.ROOT,
                    "Slope to (%d,%d) is %.1f°, limit is %.0f°", toX, toY, slope, MAX_SLOPE_DEGREES), rover);
        }
        double cost = terrain.surfaceAt(toX, toY) == SurfaceType.SAND ? MOVE_COST_SAND : MOVE_COST_GROUND;
        if (rover.batteryPercent() < cost) {
            return new StepOutcome.Failed(FailureCode.BATTERY_EXHAUSTED, String.format(Locale.ROOT,
                    "Next meter needs %.1f%% battery, rover has %.2f%%", cost, rover.batteryPercent()), rover);
        }
        return new StepOutcome.Advanced(rover.movedTo(toX, toY, cost));
    }

    private static StepOutcome turnTowards(RoverSnapshot rover, Heading target) {
        int turns = quarterTurns(rover.heading(), target);
        if (turns == 0) {
            return new StepOutcome.Advanced(rover);
        }
        Heading[] headings = Heading.values();
        int direction = Integer.signum(turns);
        Heading next = headings[(rover.heading().ordinal() + direction + headings.length) % headings.length];
        return new StepOutcome.Advanced(rover.facing(next, ROTATE_COST));
    }

    private static StepOutcome store(RoverSnapshot rover, int storagePercent, double cost) {
        if (rover.storageUsedPercent() + storagePercent > 100) {
            return new StepOutcome.Failed(FailureCode.STORAGE_FULL,
                    "Storage is " + rover.storageUsedPercent() + "% full", rover);
        }
        if (rover.batteryPercent() < cost) {
            return new StepOutcome.Failed(FailureCode.LOW_BATTERY, String.format(Locale.ROOT,
                    "Needs %.1f%% battery, rover has %.2f%%", cost, rover.batteryPercent()), rover);
        }
        return new StepOutcome.Advanced(rover.stored(storagePercent, cost));
    }

    /**
     * Signed number of 90° turns the shortest way: positive clockwise, negative counter-clockwise.
     * Headings are declared clockwise (NORTH, EAST, SOUTH, WEST); a 180° turn goes clockwise.
     */
    static int quarterTurns(Heading from, Heading to) {
        int clockwise = Math.floorMod(to.ordinal() - from.ordinal(), 4);
        return clockwise == 3 ? -1 : clockwise;
    }

    private static int dx(Heading heading) {
        return switch (heading) {
            case EAST -> 1;
            case WEST -> -1;
            case NORTH, SOUTH -> 0;
        };
    }

    /** Row 0 is the north edge, so moving north decreases y. */
    private static int dy(Heading heading) {
        return switch (heading) {
            case NORTH -> -1;
            case SOUTH -> 1;
            case EAST, WEST -> 0;
        };
    }

    /**
     * What the environment does to a step.
     *
     * @param dustStorm  solar charging is reduced
     * @param wheelStuck this meter of a drive fails
     */
    public record Conditions(boolean dustStorm, boolean wheelStuck) {

        public static final Conditions NOMINAL = new Conditions(false, false);
    }

    /**
     * @param totalSteps   0 for commands with nothing to do (e.g. rotate to the current heading)
     * @param stepDuration time each step takes
     */
    public record Plan(int totalSteps, Duration stepDuration) {
    }
}
