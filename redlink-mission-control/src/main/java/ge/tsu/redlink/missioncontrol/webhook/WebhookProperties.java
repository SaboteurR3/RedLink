package ge.tsu.redlink.missioncontrol.webhook;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.webhooks.*}.
 *
 * @param dispatcherEnabled deliver on a schedule (tests drive the dispatcher by hand)
 * @param allowHttp         accept plain-http URLs (local development only)
 * @param allowPrivateHosts accept private/loopback addresses (local development and tests only)
 * @param maxPerUser        webhooks one user may register
 * @param maxAttempts       delivery attempts before giving up
 */
@Validated
@ConfigurationProperties("redlink.webhooks")
public record WebhookProperties(
        boolean dispatcherEnabled,
        boolean allowHttp,
        boolean allowPrivateHosts,
        @Min(1) int maxPerUser,
        @Min(1) int maxAttempts,
        @NotNull Duration pollInterval,
        @Min(1) int batchSize,
        @NotNull Duration lease,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {

    public WebhookProperties {
        if (lease != null && connectTimeout != null && readTimeout != null
                && lease.compareTo(connectTimeout.plus(readTimeout)) <= 0) {
            throw new IllegalArgumentException("redlink.webhooks.lease must be longer than connect-timeout + read-timeout");
        }
    }

    /** Delay before retry number {@code attempt} (1-based): initial × 2^(attempt−1), capped. */
    public Duration backoff(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 30);
        Duration delay = initialBackoff.multipliedBy(1L << doublings);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
