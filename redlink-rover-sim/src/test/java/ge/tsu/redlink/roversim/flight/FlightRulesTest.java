package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.terrain.SiteInfo;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static ge.tsu.redlink.contract.terrain.SurfaceType.GROUND;
import static ge.tsu.redlink.contract.terrain.SurfaceType.ROCK;
import static ge.tsu.redlink.contract.terrain.SurfaceType.SAND;
import static org.assertj.core.api.Assertions.assertThat;

class FlightRulesTest {

    /*
     * 5x3 test site, 1 m cells, level 1 = 1 cm (max 2.55 m). Rover starts at (2,1).
     *
     *   x:   0      1      2      3      4
     *   y0:  GROUND ROCK   GROUND GROUND GROUND
     *   y1:  GROUND GROUND START  SAND   GROUND     (4,1) is 1 m higher: 45° from (3,1)
     *   y2:  GROUND GROUND GROUND GROUND GROUND
     */
    private static final SiteInfo SITE = new SiteInfo("test", "Test", 5, 3, 1.0, 0.0, 2.55, 2, 1);
    private static final Terrain TERRAIN = new Terrain(SITE,
            new int[]{
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 100,
                    0, 0, 0, 0, 0},
            new SurfaceType[]{
                    GROUND, ROCK, GROUND, GROUND, GROUND,
                    GROUND, GROUND, GROUND, SAND, GROUND,
                    GROUND, GROUND, GROUND, GROUND, GROUND});

    private static RoverSnapshot rover(int x, int y, Heading heading, double battery, int storage) {
        return new RoverSnapshot(x, y, heading, battery, -20, storage, RoverMode.NOMINAL);
    }

    private static StepOutcome step(RoverCommand command, RoverSnapshot rover) {
        return FlightRules.step(command, rover, TERRAIN);
    }

    @Test
    void moveGoesOneCellNorthAndCostsOnePercentOnGround() {
        StepOutcome outcome = step(new RoverCommand.Move(1), rover(2, 1, Heading.NORTH, 50, 0));

        assertThat(outcome).isInstanceOf(StepOutcome.Advanced.class);
        assertThat(outcome.rover().x()).isEqualTo(2);
        assertThat(outcome.rover().y()).isEqualTo(0);
        assertThat(outcome.rover().batteryPercent()).isEqualTo(49.0);
    }

    @Test
    void sandCostsDouble() {
        StepOutcome outcome = step(new RoverCommand.Move(1), rover(2, 1, Heading.EAST, 50, 0));

        assertThat(outcome.rover().x()).isEqualTo(3);
        assertThat(outcome.rover().batteryPercent()).isEqualTo(48.0);
    }

    @Test
    void rockAheadIsAnObstacleAndNothingChanges() {
        RoverSnapshot before = rover(1, 1, Heading.NORTH, 50, 0);

        StepOutcome outcome = step(new RoverCommand.Move(1), before);

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(FailureCode.OBSTACLE);
            assertThat(failed.detail()).isEqualTo("Rock at (1,0)");
        });
        assertThat(outcome.rover()).isEqualTo(before);
    }

    @Test
    void mapEdgeIsAnObstacle() {
        StepOutcome outcome = step(new RoverCommand.Move(1), rover(0, 1, Heading.WEST, 50, 0));

        assertThat(((StepOutcome.Failed) outcome).code()).isEqualTo(FailureCode.OBSTACLE);
        assertThat(((StepOutcome.Failed) outcome).detail()).startsWith("Edge of the map");
    }

    @Test
    void slopeAboveThirtyDegreesIsTooSteep() {
        StepOutcome outcome = step(new RoverCommand.Move(1), rover(3, 1, Heading.EAST, 50, 0));

        assertThat(((StepOutcome.Failed) outcome).code()).isEqualTo(FailureCode.SLOPE_TOO_STEEP);
        assertThat(((StepOutcome.Failed) outcome).detail()).contains("45.0°");
    }

    @Test
    void notEnoughBatteryForTheNextMeterExhaustsTheRover() {
        StepOutcome outcome = step(new RoverCommand.Move(1), rover(2, 1, Heading.EAST, 1.5, 0));

        assertThat(((StepOutcome.Failed) outcome).code()).isEqualTo(FailureCode.BATTERY_EXHAUSTED);
        assertThat(outcome.rover().batteryPercent()).isEqualTo(1.5);
    }

    @Test
    void hazardsEnterSafeModeResourceProblemsDoNot() {
        assertThat(FailureCode.OBSTACLE.entersSafeMode()).isTrue();
        assertThat(FailureCode.SLOPE_TOO_STEEP.entersSafeMode()).isTrue();
        assertThat(FailureCode.BATTERY_EXHAUSTED.entersSafeMode()).isTrue();
        assertThat(FailureCode.STORAGE_FULL.entersSafeMode()).isFalse();
        assertThat(FailureCode.LOW_BATTERY.entersSafeMode()).isFalse();
    }

    @ParameterizedTest(name = "{0} → {1}: {2} quarter turns")
    @CsvSource({
            "NORTH, NORTH, 0",
            "NORTH, EAST, 1",
            "NORTH, SOUTH, 2",
            "NORTH, WEST, -1",
            "WEST, NORTH, 1",
            "EAST, NORTH, -1"})
    void rotationTakesTheShortestWay(Heading from, Heading to, int turns) {
        assertThat(FlightRules.quarterTurns(from, to)).isEqualTo(turns);
    }

    @Test
    void rotationStepsTurnNinetyDegreesTowardsTheTarget() {
        RoverSnapshot rover = rover(2, 1, Heading.NORTH, 50, 0);

        RoverSnapshot afterOne = step(new RoverCommand.Rotate(Heading.WEST), rover).rover();

        assertThat(afterOne.heading()).isEqualTo(Heading.WEST);
        assertThat(afterOne.batteryPercent()).isEqualTo(49.8);
        assertThat(FlightRules.plan(new RoverCommand.Rotate(Heading.SOUTH), rover, Duration.ofSeconds(1)).totalSteps())
                .isEqualTo(2);
    }

    @Test
    void photoUsesStorageAndFailsWhenFull() {
        assertThat(step(new RoverCommand.TakePhoto(Camera.MASTCAM), rover(2, 1, Heading.NORTH, 50, 10)).rover()
                .storageUsedPercent()).isEqualTo(12);

        StepOutcome full = step(new RoverCommand.TakePhoto(Camera.MASTCAM), rover(2, 1, Heading.NORTH, 50, 99));
        assertThat(((StepOutcome.Failed) full).code()).isEqualTo(FailureCode.STORAGE_FULL);
    }

    @Test
    void drillingNeedsThirtyPercentBattery() {
        StepOutcome low = step(new RoverCommand.DrillSample(), rover(2, 1, Heading.NORTH, 29.99, 0));
        StepOutcome ok = step(new RoverCommand.DrillSample(), rover(2, 1, Heading.NORTH, 30, 0));

        assertThat(((StepOutcome.Failed) low).code()).isEqualTo(FailureCode.LOW_BATTERY);
        assertThat(ok.rover().batteryPercent()).isEqualTo(22.0);
        assertThat(ok.rover().storageUsedPercent()).isEqualTo(5);
    }

    @Test
    void chargingStopsAtOneHundredPercent() {
        assertThat(step(new RoverCommand.Charge(5), rover(2, 1, Heading.NORTH, 99.5, 0)).rover().batteryPercent())
                .isEqualTo(100.0);
    }

    @Test
    void rebootLeavesSafeMode() {
        RoverSnapshot safe = rover(2, 1, Heading.NORTH, 50, 0).inMode(RoverMode.SAFE_MODE);

        assertThat(step(new RoverCommand.Reboot(), safe).rover().mode()).isEqualTo(RoverMode.NOMINAL);
    }

    @Test
    void plansMatchTheCommandDictionary() {
        RoverSnapshot rover = rover(2, 1, Heading.NORTH, 50, 0);
        Duration minute = Duration.ofMillis(500);

        assertThat(FlightRules.plan(new RoverCommand.Move(7), rover, minute))
                .isEqualTo(new FlightRules.Plan(7, Duration.ofSeconds(1)));
        assertThat(FlightRules.plan(new RoverCommand.Rotate(Heading.NORTH), rover, minute).totalSteps()).isZero();
        assertThat(FlightRules.plan(new RoverCommand.TakePhoto(Camera.NAVCAM), rover, minute))
                .isEqualTo(new FlightRules.Plan(1, Duration.ofSeconds(3)));
        assertThat(FlightRules.plan(new RoverCommand.DrillSample(), rover, minute))
                .isEqualTo(new FlightRules.Plan(1, Duration.ofSeconds(15)));
        assertThat(FlightRules.plan(new RoverCommand.Charge(30), rover, minute)).isEqualTo(new FlightRules.Plan(30, minute));
        assertThat(FlightRules.plan(new RoverCommand.Reboot(), rover, minute))
                .isEqualTo(new FlightRules.Plan(1, Duration.ofSeconds(5)));
    }

    @Test
    void dustStormCutsChargingToAQuarter() {
        StepOutcome outcome = FlightRules.step(new RoverCommand.Charge(5), rover(2, 1, Heading.NORTH, 50, 0), TERRAIN,
                new FlightRules.Conditions(true, false));

        assertThat(outcome.rover().batteryPercent()).isEqualTo(50.25);
    }

    @Test
    void stuckWheelStopsTheDriveWithoutSafeMode() {
        RoverSnapshot before = rover(2, 1, Heading.EAST, 50, 0);

        StepOutcome outcome = FlightRules.step(new RoverCommand.Move(1), before, TERRAIN, new FlightRules.Conditions(false, true));

        assertThat(outcome).isInstanceOfSatisfying(StepOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(FailureCode.WHEEL_STUCK);
            assertThat(failed.code().entersSafeMode()).isFalse();
            assertThat(failed.rover()).as("nothing moved").isEqualTo(before);
        });
    }
}
