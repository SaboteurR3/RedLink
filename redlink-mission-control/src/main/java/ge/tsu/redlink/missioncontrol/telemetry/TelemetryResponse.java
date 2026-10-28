package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.contract.telemetry.TelemetryType;

import java.time.Instant;
import java.util.UUID;

/**
 * A received telemetry packet.
 *
 * @param roverTime  when it happened on Mars
 * @param receivedAt when Earth received it (about one light time later)
 */
public record TelemetryResponse(
        UUID packetId,
        TelemetryType type,
        Long commandId,
        Instant roverTime,
        Instant receivedAt,
        TelemetryPayload payload) {
}
