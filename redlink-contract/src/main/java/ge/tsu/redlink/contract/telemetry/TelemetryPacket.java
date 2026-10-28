package ge.tsu.redlink.contract.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import ge.tsu.redlink.contract.link.TraceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Downlink packet, Mars to Earth.
 *
 * @param packetId    unique per packet and reused on retransmission, so Earth can drop packets it
 *                    has already received
 * @param roverId     reporting rover
 * @param roverTime   when it happened on Mars (Earth learns about it one link delay later)
 * @param payload     what is reported
 * @param traceparent W3C trace context of the command this reports on; optional (reports that no
 *                    command caused, like weather, start their own trace)
 */
public record TelemetryPacket(
        @NotNull UUID packetId,
        @Positive long roverId,
        @NotNull Instant roverTime,
        @NotNull @Valid TelemetryPayload payload,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Pattern(regexp = TraceContext.TRACEPARENT_PATTERN) String traceparent) {

    /** Without trace context. */
    public TelemetryPacket(UUID packetId, long roverId, Instant roverTime, TelemetryPayload payload) {
        this(packetId, roverId, roverTime, payload, null);
    }
}
