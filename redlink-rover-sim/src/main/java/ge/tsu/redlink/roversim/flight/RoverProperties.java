package ge.tsu.redlink.roversim.flight;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.rover.*}: flight software timing.
 *
 * @param executorEnabled run the executor on a schedule (tests switch it off and drive it by hand)
 * @param tick            how often the executor looks for work
 * @param reorderTimeout  how long to wait for a missing sequence number before skipping it
 * @param chargeMinute    real time that one simulated charging minute takes
 */
@Validated
@ConfigurationProperties("redlink.rover")
public record RoverProperties(
        boolean executorEnabled,
        @NotNull Duration tick,
        @NotNull Duration reorderTimeout,
        @NotNull Duration chargeMinute) {
}
