package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Locale;

/**
 * Earth's model of the rover: plays a whole plan forward from the last known state on the site's
 * terrain, with the rover's published {@link RoverSpecs}, step by step and meter by meter, the
 * same way the rover's flight software will. The first step the rover would fail makes the plan
 * infeasible; otherwise the result is where the rover will be afterwards.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MissionPlanner {

    /**
     * @param steps the plan, in order
     * @return {@link Result.Feasible} with the forecast, or {@link Result.Infeasible} naming the first impossible step
     */
    public static Result plan(State start, List<RoverCommand> steps, Terrain terrain) {
        State state = start;
        for (int i = 0; i < steps.size(); i++) {
            RoverCommand step = steps.get(i);
            if (state.mode() == RoverMode.SAFE_MODE && !step.type().allowedInSafeMode()) {
                return new Result.Infeasible(i, step, "the rover is in safe mode and only accepts REBOOT");
            }
            Outcome outcome = apply(step, state, terrain);
            if (outcome.problem() != null) {
                return new Result.Infeasible(i, step, outcome.problem());
            }
            state = outcome.state();
        }
        return new Result.Feasible(state);
    }

    private static Outcome apply(RoverCommand step, State rover, Terrain terrain) {
        // Java 17: switch on the type enum keeps the exhaustiveness check.
        return switch (step.type()) {
            case MOVE -> move(rover, ((RoverCommand.Move) step).distanceMeters(), terrain);
            case ROTATE -> rotate(rover, ((RoverCommand.Rotate) step).heading());
            case TAKE_PHOTO -> store(rover, RoverSpecs.PHOTO_STORAGE, RoverSpecs.PHOTO_COST);
            case DRILL_SAMPLE -> rover.battery() < RoverSpecs.DRILL_MIN_BATTERY
                    ? Outcome.fail(String.format(Locale.ROOT, "drilling needs %.0f%% battery, the rover would have %.2f%%",
                    RoverSpecs.DRILL_MIN_BATTERY, rover.battery()))
                    : store(rover, RoverSpecs.DRILL_STORAGE, RoverSpecs.DRILL_COST);
            case CHARGE -> Outcome.ok(rover.withBattery(Math.min(100,
                    rover.battery() + ((RoverCommand.Charge) step).minutes() * RoverSpecs.CHARGE_PER_MINUTE)));
            case REBOOT -> Outcome.ok(rover.withMode(RoverMode.NOMINAL));
        };
    }

    private static Outcome move(State rover, int meters, Terrain terrain) {
        State state = rover;
        for (int meter = 1; meter <= meters; meter++) {
            int toX = state.x() + dx(state.heading());
            int toY = state.y() + dy(state.heading());
            String where = "(" + toX + "," + toY + ")";
            if (!terrain.contains(toX, toY)) {
                return Outcome.fail("meter " + meter + " would leave the map at " + where);
            }
            if (terrain.isBlocked(toX, toY)) {
                return Outcome.fail("meter " + meter + " would hit a rock at " + where);
            }
            double slope = terrain.slopeDegrees(state.x(), state.y(), toX, toY);
            if (slope > RoverSpecs.MAX_SLOPE_DEGREES) {
                return Outcome.fail(String.format(Locale.ROOT, "meter %d climbs %.1f° to %s, the limit is %.0f°",
                        meter, slope, where, RoverSpecs.MAX_SLOPE_DEGREES));
            }
            double cost = terrain.surfaceAt(toX, toY) == SurfaceType.SAND ? RoverSpecs.MOVE_COST_SAND : RoverSpecs.MOVE_COST_GROUND;
            if (state.battery() < cost) {
                return Outcome.fail(String.format(Locale.ROOT, "meter %d needs %.1f%% battery, the rover would have %.2f%%",
                        meter, cost, state.battery()));
            }
            state = new State(toX, toY, state.heading(), drain(state.battery(), cost), state.storage(), state.mode());
        }
        return Outcome.ok(state);
    }

    /** One quarter turn at a time, the shortest way (a half turn goes clockwise), like the rover. */
    private static Outcome rotate(State rover, Heading target) {
        int clockwise = Math.floorMod(target.ordinal() - rover.heading().ordinal(), 4);
        int turns = clockwise == 3 ? 1 : clockwise;
        double battery = rover.battery();
        for (int i = 0; i < turns; i++) {
            battery = drain(battery, RoverSpecs.ROTATE_COST);
        }
        return Outcome.ok(new State(rover.x(), rover.y(), target, battery, rover.storage(), rover.mode()));
    }

    private static Outcome store(State rover, int storage, double cost) {
        if (rover.storage() + storage > 100) {
            return Outcome.fail("storage would be full (" + rover.storage() + "% used)");
        }
        if (rover.battery() < cost) {
            return Outcome.fail(String.format(Locale.ROOT, "needs %.1f%% battery, the rover would have %.2f%%",
                    cost, rover.battery()));
        }
        return Outcome.ok(new State(rover.x(), rover.y(), rover.heading(), drain(rover.battery(), cost),
                rover.storage() + storage, rover.mode()));
    }

    /** Two decimals after every cost, exactly as the rover keeps its battery level. */
    private static double drain(double battery, double cost) {
        return Math.round(Math.max(0, battery - cost) * 100) / 100.0;
    }

    private static int dx(Heading heading) {
        return switch (heading) {
            case EAST -> 1;
            case WEST -> -1;
            case NORTH, SOUTH -> 0;
        };
    }

    /** Row 0 is the north edge. */
    private static int dy(Heading heading) {
        return switch (heading) {
            case NORTH -> -1;
            case SOUTH -> 1;
            case EAST, WEST -> 0;
        };
    }

    /** What Earth believes about the rover at one point of the plan. */
    public record State(int x, int y, Heading heading, double battery, int storage, RoverMode mode) {

        State withBattery(double newBattery) {
            return new State(x, y, heading, Math.round(newBattery * 100) / 100.0, storage, mode);
        }

        State withMode(RoverMode newMode) {
            return new State(x, y, heading, battery, storage, newMode);
        }
    }

    public sealed interface Result {

        /** @param end where the rover will be when the plan is done */
        record Feasible(State end) implements Result {
        }

        /** @param step zero-based index of the first step the rover could not do */
        record Infeasible(int step, RoverCommand command, String reason) implements Result {
        }
    }

    private record Outcome(State state, String problem) {

        static Outcome ok(State state) {
            return new Outcome(state, null);
        }

        static Outcome fail(String problem) {
            return new Outcome(null, problem);
        }
    }
}
