package ge.tsu.redlink.roversim.downlink;

import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.roversim.security.LinkProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Queues telemetry for Earth (transactional outbox). The packet is written in the caller's
 * transaction, so it exists if and only if the change it reports was committed; the dispatcher
 * (ticket 07) sends it once its light-time delay has passed.
 */
@Service
public class TelemetryOutbox {

    private final DownlinkPacketRepository packets;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Duration oneWayDelay;

    public TelemetryOutbox(DownlinkPacketRepository packets, JsonMapper jsonMapper, Clock clock,
                           LinkProperties linkProperties) {
        this.packets = packets;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.oneWayDelay = linkProperties.oneWayDelay();
    }

    /**
     * @return the queued packet
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TelemetryPacket emit(long roverId, TelemetryPayload payload) {
        // PostgreSQL stores microseconds; truncating here keeps replies identical to what is read back later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        TelemetryPacket packet = new TelemetryPacket(UUID.randomUUID(), roverId, now, payload);
        packets.save(new DownlinkPacket(packet.packetId(), roverId, payload.type(),
                jsonMapper.writeValueAsString(packet), now, now.plus(oneWayDelay)));
        return packet;
    }
}
