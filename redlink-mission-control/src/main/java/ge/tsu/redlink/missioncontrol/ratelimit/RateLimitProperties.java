package ge.tsu.redlink.missioncontrol.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.rate-limit.*}.
 *
 * @param submissions commands and missions sent, per user
 */
@Validated
@ConfigurationProperties("redlink.rate-limit")
public record RateLimitProperties(@NotNull @Valid Limit submissions) {

    /**
     * A token bucket: up to {@code capacity} at once, refilled evenly at {@code capacity} per {@code per}.
     */
    public record Limit(@Min(1) int capacity, @NotNull Duration per) {

        public Limit {
            if (per != null && (per.isNegative() || per.isZero())) {
                throw new IllegalArgumentException("redlink.rate-limit.*.per must be positive");
            }
        }
    }
}
