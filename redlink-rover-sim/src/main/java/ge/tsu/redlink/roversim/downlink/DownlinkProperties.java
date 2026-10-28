package ge.tsu.redlink.roversim.downlink;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.downlink.*}: delivering telemetry to Earth.
 *
 * @param earthUrl          mission-control base URL, e.g. {@code http://localhost:8080}
 * @param dispatcherEnabled run the dispatcher on a schedule (tests drive it by hand)
 * @param pollInterval      how often to look for due packets
 * @param batchSize         packets claimed per poll
 * @param lease             how long a claimed packet is reserved; must exceed connect + read timeout
 * @param initialBackoff    first retry delay; doubles per attempt
 * @param maxBackoff        retry delay cap
 */
@Validated
@ConfigurationProperties("redlink.downlink")
public record DownlinkProperties(
        @NotBlank String earthUrl,
        boolean dispatcherEnabled,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotNull Duration lease,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    public DownlinkProperties {
        if (lease != null && connectTimeout != null && readTimeout != null
                && lease.compareTo(connectTimeout.plus(readTimeout)) <= 0) {
            throw new IllegalArgumentException("redlink.downlink.lease must be longer than connect-timeout + read-timeout");
        }
    }

    /** Delay before retry number {@code attempt} (1-based): initial × 2^(attempt−1), capped. */
    public Duration backoff(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
