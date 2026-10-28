package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PlanPreviewDtos {

    /** @param steps one command or a whole plan, as for a mission */
    public record PlanPreviewRequest(
            @NotNull @Size(min = 1, max = MissionRequest.MAX_STEPS) List<@NotNull @Valid RoverCommand> steps) {
    }

    /**
     * What would happen, by Earth's last knowledge, without sending anything.
     *
     * @param path        every position on the way (the start first), for drawing it on the map
     * @param end         where the rover would be after the last step it could do
     * @param problem     the first step it could not do, or null
     * @param marsSeconds working time on Mars, without charging (its minute is a rover setting)
     * @param roverBusy   unfinished commands exist, so the real start may differ from the last report
     */
    public record PlanPreviewResponse(
            boolean feasible,
            List<PathPoint> path,
            MissionResponse.Forecast end,
            PlanProblem problem,
            double marsSeconds,
            int chargeMinutes,
            boolean roverBusy) {
    }

    /** @param x grid column of the target cell, @param y grid row */
    public record RouteRequest(@Min(0) int x, @Min(0) int y) {
    }

    /** @param steps the drive as commands; send them as a mission */
    public record RouteResponse(List<RoverCommand> steps, PlanPreviewResponse preview) {
    }

    /** @param step zero-based index of the step that led here; -1 for the start */
    public record PathPoint(int step, int x, int y, Heading heading, double batteryPercent, int storageUsedPercent) {
    }

    /**
     * @param step one-based, as people count
     * @param x    the cell a failing drive would have entered, if the problem is on the way
     */
    public record PlanProblem(int step, String reason, Integer x, Integer y) {
    }
}
