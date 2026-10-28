package ge.tsu.redlink.missioncontrol.rover;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A rover as the API shows it. {@code lastKnown} is what Earth last heard, never live truth.
 */
public record RoverResponse(long id, String name, String siteId, LastKnownState lastKnown) {

    public static RoverResponse of(Rover rover) {
        return new RoverResponse(rover.getId(), rover.getName(), rover.getSiteId(), new LastKnownState(
                rover.getLastX(), rover.getLastY(), rover.getLastHeading(), rover.getLastBatteryPercent(),
                rover.getLastTemperatureCelsius(), rover.getLastStorageUsedPercent(), rover.getLastMode(),
                rover.getLastTelemetryAt()));
    }

    /**
     * @param reportedAt Mars time of the telemetry this state comes from; {@code null} if nothing
     *                   has been heard since landing (the state is the planned landing state)
     */
    public record LastKnownState(
            int x,
            int y,
            Heading heading,
            double batteryPercent,
            double temperatureCelsius,
            int storageUsedPercent,
            RoverMode mode,
            @Schema(nullable = true, description = "Mars time of the telemetry this state comes from; null = nothing heard since landing")
            Instant reportedAt) {
    }
}
