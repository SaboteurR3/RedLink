package ge.tsu.redlink.missioncontrol.uplink;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.uplink.*}: sending commands to the rover.
 *
 * @param roverUrl       rover-sim base URL, e.g. {@code http://localhost:8081}
 * @param enabled        run transmitter and dispatcher on a schedule (tests drive them by hand)
 * @param queueHold      how long a QUEUED command waits before transmission: the last chance to cancel
 * @param oneWayDelay    simulated light time Earth → Mars (real: 3–22 minutes)
 * @param pollInterval   how often to look for work
 * @param batchSize      commands transmitted / packets delivered per round
 * @param lease          how long a claimed packet is reserved; must exceed connect + read timeout
 * @param initialBackoff first retry delay; doubles per attempt up to {@code maxBackoff}
 */
@Validated
@ConfigurationProperties("redlink.uplink")
public record UplinkProperties(
        @NotBlank String roverUrl,
        boolean enabled,
        @NotNull Duration queueHold,
        @NotNull Duration oneWayDelay,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotNull Duration lease,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    public UplinkProperties {
        if (lease != null && connectTimeout != null && readTimeout != null
                && lease.compareTo(connectTimeout.plus(readTimeout)) <= 0) {
            throw new IllegalArgumentException("redlink.uplink.lease must be longer than connect-timeout + read-timeout");
        }
    }

    /** Delay before retry number {@code attempt} (1-based): initial × 2^(attempt−1), capped. */
    public Duration backoff(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
