package ge.tsu.redlink.missioncontrol.mission;

import com.fasterxml.jackson.annotation.JsonProperty;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.command.CommandResponse.Link;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param status   derived from the steps
 * @param forecast where Earth predicted the rover would be after the last step, when the plan was checked
 * @param steps    the steps in order, each a full command
 */
public record MissionResponse(
        long id,
        long roverId,
        String name,
        MissionStatus status,
        Progress progress,
        Forecast forecast,
        String createdBy,
        Instant createdAt,
        List<CommandResponse> steps,
        @JsonProperty("_links")
        @Schema(description = "self, rover; cancel while any step is still on Earth")
        Map<String, Link> links) {

    static String selfPath(long missionId) {
        return "/api/v1/missions/" + missionId;
    }

    public record Progress(int completed, int total) {
    }

    public record Forecast(int x, int y, Heading heading, double batteryPercent, int storageUsedPercent, RoverMode mode) {

        static Forecast of(MissionPlanner.State state) {
            return new Forecast(state.x(), state.y(), state.heading(), state.battery(), state.storage(), state.mode());
        }
    }
}
