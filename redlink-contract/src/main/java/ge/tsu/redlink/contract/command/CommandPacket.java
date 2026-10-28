package ge.tsu.redlink.contract.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Uplink packet, Earth to Mars.
 *
 * @param packetId   unique per packet and reused on retransmission, so the rover can drop
 *                   packets it has already received
 * @param roverId    target rover
 * @param commandId  Earth-side command id, echoed back in command status telemetry
 * @param sequenceNo per-rover order; the rover executes commands in this order, whatever order
 *                   the packets arrive in
 * @param command    what to do
 * @param sentAt     when Earth transmitted the packet
 */
public record CommandPacket(
        @NotNull UUID packetId,
        @Positive long roverId,
        @Positive long commandId,
        @Positive long sequenceNo,
        @NotNull @Valid RoverCommand command,
        @NotNull Instant sentAt) {
}
