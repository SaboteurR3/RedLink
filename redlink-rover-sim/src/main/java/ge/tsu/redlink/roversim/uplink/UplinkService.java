package ge.tsu.redlink.roversim.uplink;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.roversim.downlink.TelemetryOutbox;
import ge.tsu.redlink.roversim.rover.RoverStateRepository;
import ge.tsu.redlink.roversim.tracing.LinkTracing;
import ge.tsu.redlink.roversim.uplink.UplinkExceptions.UnknownRoverException;
import ge.tsu.redlink.roversim.uplink.UplinkExceptions.UplinkConflictException;
import ge.tsu.redlink.roversim.uplink.UplinkExceptions.UplinkConflictException.Kind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Receives uplinked commands. The deep-space link delivers at least once (Earth retries until it
 * gets an answer), so the same packet may arrive several times; it takes effect exactly once.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UplinkService {

    private final ReceivedCommandRepository commands;
    private final RoverStateRepository rovers;
    private final TelemetryOutbox telemetry;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final LinkTracing tracing;

    /**
     * Stores the command and queues a RECEIVED report for Earth, both in one transaction.
     *
     * @throws UnknownRoverException   if no such rover exists
     * @throws UplinkConflictException if the packet id or sequence slot belongs to a different packet
     */
    @Transactional
    public UplinkReceipt receive(CommandPacket packet) {
        // Continue Earth's trace from the signed packet, so the RECEIVED report carries it too.
        return tracing.inSpan(packet.traceparent(), "rover receive", () -> store(packet));
    }

    private UplinkReceipt store(CommandPacket packet) {
        if (!rovers.existsById(packet.roverId())) {
            throw new UnknownRoverException(packet.roverId());
        }
        // PostgreSQL stores microseconds; truncating here keeps replies identical to what is read back later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int inserted = commands.insertIfAbsent(packet.packetId(), packet.roverId(), packet.commandId(),
                packet.sequenceNo(), packet.command().type().name(), jsonMapper.writeValueAsString(packet.command()),
                packet.sentAt(), now, packet.traceparent());

        if (inserted == 0) {
            return duplicateOrConflict(packet);
        }
        telemetry.emit(packet.roverId(), new TelemetryPayload.CommandStatus(
                packet.commandId(), CommandExecutionStatus.RECEIVED, 0, null, null));
        log.info("Received command {} {} for rover {} (sequence {})",
                packet.commandId(), packet.command().type(), packet.roverId(), packet.sequenceNo());
        return new UplinkReceipt(packet.packetId(), packet.commandId(), false, now);
    }

    private UplinkReceipt duplicateOrConflict(CommandPacket packet) {
        ReceivedCommand existing = commands.findById(packet.packetId()).orElseThrow(() ->
                new UplinkConflictException(Kind.SEQUENCE_TAKEN, "Sequence number " + packet.sequenceNo()
                        + " of rover " + packet.roverId() + " is already taken by another packet"));

        if (!sameContent(existing, packet)) {
            throw new UplinkConflictException(Kind.PACKET_ID_REUSED,
                    "Packet " + packet.packetId() + " was already received with different content");
        }
        log.debug("Duplicate delivery of packet {} ignored", packet.packetId());
        return new UplinkReceipt(existing.getPacketId(), existing.getCommandId(), true, existing.getReceivedAt());
    }

    /** {@code sentAt} is ignored on purpose: a retransmission may be stamped later. */
    private boolean sameContent(ReceivedCommand existing, CommandPacket packet) {
        return existing.getRoverId() == packet.roverId()
                && existing.getCommandId() == packet.commandId()
                && existing.getSequenceNo() == packet.sequenceNo()
                && jsonMapper.readValue(existing.getCommand(), RoverCommand.class).equals(packet.command());
    }

    public record UplinkReceipt(UUID packetId, long commandId, boolean duplicate, Instant receivedAt) {
    }
}
