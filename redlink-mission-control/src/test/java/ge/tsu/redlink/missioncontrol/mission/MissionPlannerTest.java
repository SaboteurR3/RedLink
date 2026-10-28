package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.terrain.SiteInfo;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.missioncontrol.mission.MissionPlanner.Result;
import ge.tsu.redlink.missioncontrol.mission.MissionPlanner.State;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MissionPlannerTest {

    /*
     * 5×5, 1 m cells, 0–10 m elevation. Rover starts at (2,4) facing north.
     *   row 0:  .  .  R  .  .      R = rock
     *   row 1:  .  .  S  .  .      S = sand
     *   row 2:  .  .  .  .  .
     *   row 3:  .  .  .  .  H      H = 2 m higher than its neighbours (63° slope)
     *   row 4:  .  .  *  .  .
     */
    private static final Terrain TERRAIN = terrain();
    private static final State START = new State(2, 4, Heading.NORTH, 50, 0, RoverMode.NOMINAL);

    @Test
    void feasiblePlanForecastsTheEndState() {
        Result result = MissionPlanner.plan(START, List.of(
                new RoverCommand.Move(3),                  // to (2,1), last meter in sand: 1 + 1 + 2
                new RoverCommand.TakePhoto(Camera.NAVCAM), // 0.5, storage +2
                new RoverCommand.Rotate(Heading.SOUTH),    // half turn: 2 × 0.2
                new RoverCommand.Charge(10)), TERRAIN);

        assertThat(result).isEqualTo(new Result.Feasible(new State(2, 1, Heading.SOUTH, 55.1, 2, RoverMode.NOMINAL)));
    }

    @Test
    void rockNamesTheStepAndTheMeter() {
        Result result = MissionPlanner.plan(START, List.of(new RoverCommand.Rotate(Heading.NORTH), new RoverCommand.Move(5)),
                TERRAIN);

        assertThat(result).isInstanceOfSatisfying(Result.Infeasible.class, infeasible -> {
            assertThat(infeasible.step()).isEqualTo(1);
            assertThat(infeasible.reason()).isEqualTo("meter 4 would hit a rock at (2,0)");
        });
    }

    @Test
    void mapEdge() {
        State facingWest = new State(0, 2, Heading.WEST, 50, 0, RoverMode.NOMINAL);

        assertThat(reason(MissionPlanner.plan(facingWest, List.of(new RoverCommand.Move(1)), TERRAIN)))
                .isEqualTo("meter 1 would leave the map at (-1,2)");
    }

    @Test
    void steepSlope() {
        State belowHill = new State(4, 4, Heading.NORTH, 50, 0, RoverMode.NOMINAL);

        assertThat(reason(MissionPlanner.plan(belowHill, List.of(new RoverCommand.Move(1)), TERRAIN)))
                .startsWith("meter 1 climbs 63.4° to (4,3)");
    }

    @Test
    void batteryRunsOutHalfwayThroughTheDrive() {
        State low = new State(2, 4, Heading.NORTH, 2.5, 0, RoverMode.NOMINAL);

        assertThat(reason(MissionPlanner.plan(low, List.of(new RoverCommand.Move(3)), TERRAIN)))
                .isEqualTo("meter 3 needs 2.0% battery, the rover would have 0.50%");
    }

    @Test
    void drillingNeedsThirtyPercentWhenItsTurnComes() {
        State state = new State(2, 4, Heading.NORTH, 31, 0, RoverMode.NOMINAL);

        Result result = MissionPlanner.plan(state, List.of(new RoverCommand.Move(2), new RoverCommand.DrillSample()), TERRAIN);

        assertThat(result).isInstanceOfSatisfying(Result.Infeasible.class, infeasible -> {
            assertThat(infeasible.step()).isEqualTo(1);
            assertThat(infeasible.reason()).isEqualTo("drilling needs 30% battery, the rover would have 29.00%");
        });
    }

    @Test
    void chargingFirstMakesTheDrillPossible() {
        State state = new State(2, 4, Heading.NORTH, 25, 0, RoverMode.NOMINAL);

        assertThat(MissionPlanner.plan(state, List.of(new RoverCommand.Charge(10), new RoverCommand.DrillSample()), TERRAIN))
                .isEqualTo(new Result.Feasible(new State(2, 4, Heading.NORTH, 27, 5, RoverMode.NOMINAL)));
    }

    @Test
    void fullStorage() {
        State full = new State(2, 4, Heading.NORTH, 50, 99, RoverMode.NOMINAL);

        assertThat(reason(MissionPlanner.plan(full, List.of(new RoverCommand.TakePhoto(Camera.MASTCAM)), TERRAIN)))
                .isEqualTo("storage would be full (99% used)");
    }

    @Test
    void safeModeAcceptsOnlyRebootFirst() {
        State safe = new State(2, 4, Heading.NORTH, 50, 0, RoverMode.SAFE_MODE);

        assertThat(reason(MissionPlanner.plan(safe, List.of(new RoverCommand.Move(1)), TERRAIN)))
                .isEqualTo("the rover is in safe mode and only accepts REBOOT");
        assertThat(MissionPlanner.plan(safe, List.of(new RoverCommand.Reboot(), new RoverCommand.Move(1)), TERRAIN))
                .isInstanceOf(Result.Feasible.class);
    }

    private static String reason(Result result) {
        assertThat(result).isInstanceOf(Result.Infeasible.class);
        return ((Result.Infeasible) result).reason();
    }

    private static Terrain terrain() {
        int[] levels = new int[25];
        SurfaceType[] surface = new SurfaceType[25];
        Arrays.fill(surface, SurfaceType.GROUND);
        surface[2] = SurfaceType.ROCK;     // (2,0)
        surface[1 * 5 + 2] = SurfaceType.SAND; // (2,1)
        levels[3 * 5 + 4] = 51;            // (4,3): 51/255 × 10 m = 2 m
        return new Terrain(new SiteInfo("test-site", "Test", 5, 5, 1.0, 0, 10, 2, 4), levels, surface);
    }
}
