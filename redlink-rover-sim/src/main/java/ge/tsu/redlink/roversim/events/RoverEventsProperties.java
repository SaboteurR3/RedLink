package ge.tsu.redlink.roversim.events;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Random events on Mars.
 *
 * @param weatherInterval          how often the weather may change
 * @param dustStormChancePerMinute probability that a storm starts within one minute of clear sky
 * @param dustStormDuration        how long a storm lasts
 * @param wheelStuckChancePerMeter probability that one meter of driving gets a wheel stuck
 */
@Validated
@ConfigurationProperties("redlink.rover.events")
public record RoverEventsProperties(
        boolean enabled,
        @NotNull Duration weatherInterval,
        @DecimalMin("0.0") @DecimalMax("1.0") double dustStormChancePerMinute,
        @NotNull Duration dustStormDuration,
        @DecimalMin("0.0") @DecimalMax("1.0") double wheelStuckChancePerMeter) {

    /** The per-minute chance converted to one weather check, so the rate does not depend on the interval. */
    public double dustStormChancePerCheck() {
        double minutes = weatherInterval.toMillis() / 60_000.0;
        return 1 - Math.pow(1 - dustStormChancePerMinute, minutes);
    }
}
