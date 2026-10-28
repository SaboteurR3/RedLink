package ge.tsu.redlink.contract.telemetry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * What a telemetry packet reports. On the wire the type travels inside the object:
 * {@code {"type": "ROVER_STATE", "x": 10, ...}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TelemetryPayload.CommandStatus.class, name = "COMMAND_STATUS"),
        @JsonSubTypes.Type(value = TelemetryPayload.RoverState.class, name = "ROVER_STATE"),
        @JsonSubTypes.Type(value = TelemetryPayload.Event.class, name = "EVENT"),
        @JsonSubTypes.Type(value = TelemetryPayload.Photo.class, name = "PHOTO"),
        @JsonSubTypes.Type(value = TelemetryPayload.Sample.class, name = "SAMPLE")
})
public sealed interface TelemetryPayload {

    @JsonIgnore
    TelemetryType type();

    /**
     * @param commandId       Earth-side id from the {@link ge.tsu.redlink.contract.command.CommandPacket}
     * @param progressPercent 0 until executing, 100 when completed
     * @param failureCode     machine-readable reason when FAILED or REFUSED, e.g. {@code OBSTACLE}
     * @param failureDetail   human-readable explanation when FAILED or REFUSED
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CommandStatus(
            @Positive long commandId,
            @NotNull CommandExecutionStatus status,
            @Min(0) @Max(100) int progressPercent,
            @Size(max = 64) String failureCode,
            @Size(max = 500) String failureDetail) implements TelemetryPayload {

        @Override
        public TelemetryType type() {
            return TelemetryType.COMMAND_STATUS;
        }
    }

    /**
     * @param x                  grid column, in meters from the west edge
     * @param y                  grid row, in meters from the north edge
     * @param storageUsedPercent onboard storage used by photos and samples
     */
    record RoverState(
            @PositiveOrZero int x,
            @PositiveOrZero int y,
            @NotNull Heading heading,
            @DecimalMin("0.0") @DecimalMax("100.0") double batteryPercent,
            double temperatureCelsius,
            @Min(0) @Max(100) int storageUsedPercent,
            @NotNull RoverMode mode) implements TelemetryPayload {

        @Override
        public TelemetryType type() {
            return TelemetryType.ROVER_STATE;
        }
    }

    /**
     * @param code machine-readable event code, e.g. {@code DUST_STORM_STARTED}
     */
    record Event(
            @NotBlank @Size(max = 64) String code,
            @NotNull EventSeverity severity,
            @NotBlank @Size(max = 500) String message) implements TelemetryPayload {

        @Override
        public TelemetryType type() {
            return TelemetryType.EVENT;
        }
    }

    /**
     * @param commandId   the TAKE_PHOTO command that took it
     * @param x           where the rover stood, like {@link RoverState}
     * @param imageBase64 the PNG, base64-encoded; left out where only the metadata is stored
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Photo(
            @Positive long commandId,
            @NotNull Camera camera,
            @PositiveOrZero int x,
            @PositiveOrZero int y,
            @NotNull Heading heading,
            // "iVBORw0KGgo" is the base64 of the PNG signature, so non-PNG uploads fail validation.
            @NotNull @Size(max = MAX_IMAGE_BASE64) @Pattern(regexp = "iVBORw0KGgo[A-Za-z0-9+/]*={0,2}") String imageBase64)
            implements TelemetryPayload {

        /** 2 MB of base64, about 1.5 MB of PNG. */
        public static final int MAX_IMAGE_BASE64 = 2 * 1024 * 1024;

        @Override
        public TelemetryType type() {
            return TelemetryType.PHOTO;
        }

        /** The same report without the image, for places that keep only the metadata. */
        public Photo withoutImage() {
            return new Photo(commandId, camera, x, y, heading, null);
        }
    }

    /**
     * @param commandId the DRILL_SAMPLE command that took it
     * @param material  ROCK when the rover faced a rock, otherwise SOIL from just in front of it
     * @param x         where the rover stood, like {@link RoverState}; a rock sample comes from the
     *                  next cell in {@code heading}
     */
    record Sample(
            @Positive long commandId,
            @NotNull SampleMaterial material,
            @PositiveOrZero int x,
            @PositiveOrZero int y,
            @NotNull Heading heading) implements TelemetryPayload {

        @Override
        public TelemetryType type() {
            return TelemetryType.SAMPLE;
        }
    }
}
