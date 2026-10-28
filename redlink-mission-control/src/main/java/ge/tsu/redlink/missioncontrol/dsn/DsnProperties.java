package ge.tsu.redlink.missioncontrol.dsn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param windows               relay-orbiter passes: the uplink transmits only while one is open
 * @param conjunctionRetryAfter what {@code Retry-After} tells the rover during a solar conjunction
 */
@Validated
@ConfigurationProperties("redlink.dsn")
public record DsnProperties(@NotNull @Valid Windows windows, @NotNull Duration conjunctionRetryAfter) {

    /**
     * @param every    one window starts every this long, counted from the Unix epoch
     * @param duration how long each window stays open; shorter than {@code every}
     */
    public record Windows(boolean enabled, @NotNull Duration every, @NotNull Duration duration) {

        public Windows {
            if (every != null && duration != null
                    && (duration.isNegative() || duration.isZero() || duration.compareTo(every) >= 0)) {
                throw new IllegalArgumentException("redlink.dsn.windows.duration must be positive and shorter than every");
            }
        }
    }
}
