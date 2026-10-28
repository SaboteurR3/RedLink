package ge.tsu.redlink.contract.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import ge.tsu.redlink.contract.link.TraceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * @param traceparent W3C trace context of the request that created the command; optional
 */
public record CommandPacket(
        @NotNull UUID packetId,
        @Positive long roverId,
        @Positive long commandId,
        @Positive long sequenceNo,
        @NotNull @Valid RoverCommand command,
        @NotNull Instant sentAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Pattern(regexp = TraceContext.TRACEPARENT_PATTERN) String traceparent) {

    /** Without trace context (an untraced sender). */
    public CommandPacket(UUID packetId, long roverId, long commandId, long sequenceNo, RoverCommand command,
                         Instant sentAt) {
        this(packetId, roverId, commandId, sequenceNo, command, sentAt, null);
    }
}
