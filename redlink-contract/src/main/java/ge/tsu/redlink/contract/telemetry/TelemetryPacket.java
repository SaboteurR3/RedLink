package ge.tsu.redlink.contract.telemetry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.UUID;

/**
 * Downlink packet, Mars to Earth.
 *
 * @param packetId  unique per packet and reused on retransmission, so Earth can drop packets it
 *                  has already received
 * @param roverId   reporting rover
 * @param roverTime when it happened on Mars (Earth learns about it one link delay later)
 * @param payload   what is reported
 */
public record TelemetryPacket(
        @NotNull UUID packetId,
        @Positive long roverId,
        @NotNull Instant roverTime,
        @NotNull @Valid TelemetryPayload payload) {
}
