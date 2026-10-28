package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.terrain.SiteInfo;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePlannerTest {

    /*
     * 7 × 5, flat, 1 m cells. R = rock, S = sand.
     *   row 0:  .  .  .  .  .  .  .
     *   row 1:  .  .  .  R  .  .  .
     *   row 2:  .  .  .  R  .  .  .
     *   row 3:  .  .  .  R  .  .  .
     *   row 4:  .  .  .  S  .  .  .
     */
    private static final Terrain TERRAIN = terrain();

    @Test
    void straightLineIsOneMove() {
        assertThat(RoutePlanner.route(TERRAIN, 0, 2, Heading.NORTH, 0, 0))
                .contains(List.of(new RoverCommand.Move(2)));
    }

    @Test
    void turnsFirstWhenFacingTheWrongWay() {
        assertThat(RoutePlanner.route(TERRAIN, 0, 0, Heading.SOUTH, 2, 0))
                .contains(List.of(new RoverCommand.Rotate(Heading.EAST), new RoverCommand.Move(2)));
    }

    @Test
    void goesAroundTheRockWallTheCheapWay() {
        // Through the sand cell (3,4) costs 2; over the top (row 0) costs more meters. Both avoid rocks.
        Optional<List<RoverCommand>> route = RoutePlanner.route(TERRAIN, 2, 2, Heading.NORTH, 4, 2);

        assertThat(route).isPresent();
        List<String> cells = drive(2, 2, Heading.NORTH, route.get());
        assertThat(cells).as("never enters a rock").doesNotContain("3,1", "3,2", "3,3");
        assertThat(cells.get(cells.size() - 1)).isEqualTo("4,2");
    }

    @Test
    void rockOrOutsideTheMapCannotBeReached() {
        assertThat(RoutePlanner.route(TERRAIN, 0, 0, Heading.EAST, 3, 2)).isEmpty();
        assertThat(RoutePlanner.route(TERRAIN, 0, 0, Heading.EAST, 9, 9)).isEmpty();
    }

    @Test
    void theRouteIsAPlanTheMissionPlannerAccepts() {
        List<RoverCommand> route = RoutePlanner.route(TERRAIN, 0, 4, Heading.NORTH, 6, 0).orElseThrow();
        MissionPlanner.Result result = MissionPlanner.plan(
                new MissionPlanner.State(0, 4, Heading.NORTH, 100, 0, RoverMode.NOMINAL), route, TERRAIN);

        assertThat(result).isInstanceOfSatisfying(MissionPlanner.Result.Feasible.class, f -> {
            assertThat(f.end().x()).isEqualTo(6);
            assertThat(f.end().y()).isZero();
        });
    }

    @Test
    void longDrivesAreSplitIntoMovesOfAtMostTwentyMeters() {
        Terrain wide = new Terrain(new SiteInfo("wide-site", "Wide", 50, 2, 1, 0, 1, 0, 0), new int[100], fill(100));
        assertThat(RoutePlanner.route(wide, 0, 0, Heading.EAST, 45, 0))
                .contains(List.of(new RoverCommand.Move(20), new RoverCommand.Move(20), new RoverCommand.Move(5)));
    }

    /** Replays the commands and returns every cell entered. */
    private static List<String> drive(int x, int y, Heading heading, List<RoverCommand> commands) {
        java.util.ArrayList<String> cells = new java.util.ArrayList<>();
        for (RoverCommand command : commands) {
            if (command instanceof RoverCommand.Rotate rotate) {
                heading = rotate.heading();
            } else if (command instanceof RoverCommand.Move move) {
                for (int i = 0; i < move.distanceMeters(); i++) {
                    x += heading == Heading.EAST ? 1 : heading == Heading.WEST ? -1 : 0;
                    y += heading == Heading.SOUTH ? 1 : heading == Heading.NORTH ? -1 : 0;
                    cells.add(x + "," + y);
                }
            }
        }
        return cells;
    }

    private static Terrain terrain() {
        SurfaceType[] surface = fill(35);
        surface[1 * 7 + 3] = SurfaceType.ROCK;
        surface[2 * 7 + 3] = SurfaceType.ROCK;
        surface[3 * 7 + 3] = SurfaceType.ROCK;
        surface[4 * 7 + 3] = SurfaceType.SAND;
        return new Terrain(new SiteInfo("route-site", "Route", 7, 5, 1, 0, 1, 0, 0), new int[35], surface);
    }

    private static SurfaceType[] fill(int cells) {
        SurfaceType[] surface = new SurfaceType[cells];
        Arrays.fill(surface, SurfaceType.GROUND);
        return surface;
    }
}
