package ge.tsu.redlink.missioncontrol.specs;

import ge.tsu.redlink.contract.rover.MarsClock;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;

/**
 * The rover's published spec sheet ({@link RoverSpecs}), so clients can estimate time and cost
 * with exactly the numbers the rover executes with, instead of copies that drift.
 */
@RestController
@RequestMapping("/api/v1/specs")
@Tag(name = "Specs", description = "What each rover action costs and how long it takes")
public class SpecsController {

    /** Changes only with a release, so clients may keep it for an hour and then revalidate. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePrivate();
    static final SpecsResponse SPECS = SpecsResponse.current();
    private static final String ETAG = "\"" + Integer.toHexString(Objects.hash(SPECS)) + "\"";

    @Operation(summary = "The rover's spec sheet", description = "Costs in battery and storage percent, limits, step durations.")
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<SpecsResponse> specs() {
        return ResponseEntity.ok().eTag(ETAG).cacheControl(CACHE).body(SPECS);
    }

    /**
     * @param moveCostPerMeterGround battery percent per meter on ground
     * @param moveCostPerMeterSand   battery percent per meter in sand
     * @param rotateCost             battery percent per quarter turn
     * @param dustStormChargeFactor  fraction of the normal charge rate during a dust storm
     * @param solSeconds             length of a Mars day at the site (compressed for the demo); charging
     *                               gets the sun's height as a share of the full rate, none at night
     */
    public record SpecsResponse(
            double maxSlopeDegrees,
            double moveCostPerMeterGround,
            double moveCostPerMeterSand,
            double rotateCost,
            double photoCost,
            int photoStorage,
            double drillCost,
            double drillMinBattery,
            int drillStorage,
            double chargePerMinute,
            double dustStormChargeFactor,
            double moveSecondsPerMeter,
            double rotateSecondsPerQuarterTurn,
            double photoSeconds,
            double drillSeconds,
            double rebootSeconds,
            double solSeconds) {

        static SpecsResponse current() {
            return new SpecsResponse(RoverSpecs.MAX_SLOPE_DEGREES, RoverSpecs.MOVE_COST_GROUND, RoverSpecs.MOVE_COST_SAND,
                    RoverSpecs.ROTATE_COST, RoverSpecs.PHOTO_COST, RoverSpecs.PHOTO_STORAGE, RoverSpecs.DRILL_COST,
                    RoverSpecs.DRILL_MIN_BATTERY, RoverSpecs.DRILL_STORAGE, RoverSpecs.CHARGE_PER_MINUTE,
                    RoverSpecs.DUST_STORM_CHARGE_FACTOR, seconds(RoverSpecs.MOVE_STEP), seconds(RoverSpecs.ROTATE_STEP),
                    seconds(RoverSpecs.PHOTO_TIME), seconds(RoverSpecs.DRILL_TIME), seconds(RoverSpecs.REBOOT_TIME),
                    seconds(MarsClock.SOL));
        }

        private static double seconds(Duration duration) {
            return duration.toMillis() / 1000.0;
        }
    }
}
