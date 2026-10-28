package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.command.RoverCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * @param steps the plan, in order; each a dictionary command as in {@code POST /rovers/{id}/commands}
 */
public record MissionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(min = 1, max = MissionRequest.MAX_STEPS) List<@NotNull @Valid RoverCommand> steps) {

    public static final int MAX_STEPS = 50;
}
