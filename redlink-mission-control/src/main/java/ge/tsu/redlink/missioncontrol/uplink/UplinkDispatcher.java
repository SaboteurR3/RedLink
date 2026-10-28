package ge.tsu.redlink.missioncontrol.uplink;

import ge.tsu.redlink.missioncontrol.uplink.RoverLinkClient.Delivery;
import ge.tsu.redlink.missioncontrol.uplink.UplinkClaims.ClaimedPacket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Delivers transmitted command packets to the rover once their light time has passed. At least
 * once: the rover drops duplicates by packet id (ticket 05).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UplinkDispatcher {

    private final UplinkClaims claims;
    private final RoverLinkClient rover;
    private final UplinkRejections rejections;
    private final UplinkProperties properties;
    private final Clock clock;

    /**
     * One round: claim due packets, send each outside any transaction, record the result.
     *
     * @return number of packets attempted
     */
    public int dispatchDue() {
        Instant now = now();
        List<ClaimedPacket> batch = claims.claimDue(now, now.plus(properties.lease()), properties.batchSize());
        for (ClaimedPacket packet : batch) {
            Delivery delivery = rover.send(packet.json());
            switch (delivery.outcome()) {
                case DELIVERED -> claims.markSent(packet.packetId(), now());
                case REJECTED -> {
                    rejections.reject(packet, delivery.detail(), now());
                    log.error("Rover rejected packet {} of command {} ({}); command FAILED",
                            packet.packetId(), packet.commandId(), delivery.detail());
                }
                case RETRY -> {
                    Instant next = now().plus(properties.backoff(packet.attempts()));
                    claims.scheduleRetry(packet.packetId(), next, delivery.detail());
                    log.warn("Command packet {} not delivered (attempt {}: {}); retrying at {}",
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
