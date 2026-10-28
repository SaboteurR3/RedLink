package ge.tsu.redlink.missioncontrol.events;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.events.*}: the SSE stream.
 *
 * @param heartbeat      interval of {@code : keepalive} comments, so proxies keep idle streams open
 * @param emitterTimeout a stream is closed after this long; the client reconnects (and resumes)
 * @param maxSubscribers open streams per instance; more → 503
 * @param replayLimit    most events replayed on resume; a larger gap gets a {@code reset} instead
 * @param retention      events older than this are purged
 * @param ticketTtl      how long a stream ticket stays valid
 */
@Validated
@ConfigurationProperties("redlink.events")
public record EventProperties(
        @NotNull Duration heartbeat,
        @NotNull Duration emitterTimeout,
        @Min(1) int maxSubscribers,
        @Min(1) int replayLimit,
        @NotNull Duration retention,
        @NotNull Duration ticketTtl) {
}
