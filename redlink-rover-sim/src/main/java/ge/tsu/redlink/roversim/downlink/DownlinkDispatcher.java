package ge.tsu.redlink.roversim.downlink;

import ge.tsu.redlink.roversim.downlink.DownlinkClaims.ClaimedPacket;
import ge.tsu.redlink.roversim.downlink.EarthLinkClient.Delivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Store-and-forward for telemetry: claims packets whose light-time delay has passed, transmits
 * them, and records the outcome. Delivery is at least once (a crash after sending but before
 * recording means a resend); Earth drops duplicates by packet id.
 */
@Service
@EnableConfigurationProperties(DownlinkProperties.class)
@Slf4j
@RequiredArgsConstructor
public class DownlinkDispatcher {

    private final DownlinkClaims claims;
    private final EarthLinkClient earth;
    private final DownlinkProperties properties;
    private final Clock clock;

    /**
     * One round: claim due packets, send each, record the result. No transaction spans the HTTP
     * calls; the claim's lease keeps the packets reserved meanwhile.
     *
     * @return number of packets attempted
     */
    public int dispatchDue() {
        Instant now = now();
        List<ClaimedPacket> batch = claims.claimDue(now, now.plus(properties.lease()), properties.batchSize());
        Instant linkDownUntil = null;
        for (ClaimedPacket packet : batch) {
            if (linkDownUntil != null) {
                // Earth just said when to try again; the rest of the batch would get the same answer.
                claims.scheduleRetry(packet.packetId(), linkDownUntil, "not sent: Earth asked to wait");
                continue;
            }
            Delivery delivery = earth.send(packet.json());
            switch (delivery.outcome()) {
                case DELIVERED -> claims.markSent(packet.packetId(), now());
                case REJECTED -> {
                    claims.markRejected(packet.packetId(), delivery.detail());
                    log.error("Earth rejected telemetry packet {} ({}); it will not be resent",
                            packet.packetId(), delivery.detail());
                }
                case RETRY -> {
                    // Our own backoff, unless Earth asked for longer (Retry-After): it knows best.
                    Duration delay = properties.backoff(packet.attempts());
                    if (delivery.retryAfter() != null && delivery.retryAfter().compareTo(delay) > 0) {
                        delay = delivery.retryAfter();
                    }
                    Instant next = now().plus(delay);
                    if (delivery.retryAfter() != null) {
                        linkDownUntil = next;
                    }
                    claims.scheduleRetry(packet.packetId(), next, delivery.detail());
                    log.warn("Telemetry packet {} not delivered (attempt {}: {}); retrying at {}",
                            packet.packetId(), packet.attempts(), delivery.detail(), next);
                }
            }
        }
        return batch.size();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
