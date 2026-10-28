package ge.tsu.redlink.contract.command;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A command with its parameters. On the wire the type travels inside the object:
 * {@code {"type": "MOVE", "distanceMeters": 5}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RoverCommand.Move.class, name = "MOVE"),
        @JsonSubTypes.Type(value = RoverCommand.Rotate.class, name = "ROTATE"),
        @JsonSubTypes.Type(value = RoverCommand.TakePhoto.class, name = "TAKE_PHOTO"),
        @JsonSubTypes.Type(value = RoverCommand.DrillSample.class, name = "DRILL_SAMPLE"),
        @JsonSubTypes.Type(value = RoverCommand.Charge.class, name = "CHARGE"),
        @JsonSubTypes.Type(value = RoverCommand.Reboot.class, name = "REBOOT")
})
public sealed interface RoverCommand {

    @JsonIgnore
    CommandType type();

    /** Drive forward, one grid cell (one meter) at a time. */
    record Move(@Min(1) @Max(20) int distanceMeters) implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.MOVE;
        }
    }

    /** Turn in place until the rover faces {@code heading}. */
    record Rotate(@NotNull Heading heading) implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.ROTATE;
        }
    }

    record TakePhoto(@NotNull Camera camera) implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.TAKE_PHOTO;
        }
    }

    /** Drill a soil sample at the current position. */
    record DrillSample() implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.DRILL_SAMPLE;
        }
    }

    /** Point the solar panels at the sun for the given number of (simulated) minutes. */
    record Charge(@Min(1) @Max(120) int minutes) implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.CHARGE;
        }
    }

    /** Leave safe mode. */
    record Reboot() implements RoverCommand {
        @Override
        public CommandType type() {
            return CommandType.REBOOT;
        }
    }
}
