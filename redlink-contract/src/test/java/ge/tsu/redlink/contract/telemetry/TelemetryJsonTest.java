package ge.tsu.redlink.contract.telemetry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of(new TelemetryPayload.CommandStatus(981, CommandExecutionStatus.EXECUTING, 40, null, null),
                        "{\"type\":\"COMMAND_STATUS\",\"commandId\":981,\"status\":\"EXECUTING\",\"progressPercent\":40}"),
                Arguments.of(new TelemetryPayload.CommandStatus(981, CommandExecutionStatus.FAILED, 60, "OBSTACLE", "Rock at (12,7)"),
                        "{\"type\":\"COMMAND_STATUS\",\"commandId\":981,\"status\":\"FAILED\",\"progressPercent\":60,"
                                + "\"failureCode\":\"OBSTACLE\",\"failureDetail\":\"Rock at (12,7)\"}"),
                Arguments.of(new TelemetryPayload.RoverState(12, 7, Heading.NORTH, 78.5, -40.0, 10, RoverMode.NOMINAL),
                        "{\"type\":\"ROVER_STATE\",\"x\":12,\"y\":7,\"heading\":\"NORTH\",\"batteryPercent\":78.5,"
                                + "\"temperatureCelsius\":-40.0,\"storageUsedPercent\":10,\"mode\":\"NOMINAL\"}"),
                Arguments.of(new TelemetryPayload.Event("DUST_STORM_STARTED", EventSeverity.WARNING, "Solar gain reduced"),
                        "{\"type\":\"EVENT\",\"code\":\"DUST_STORM_STARTED\",\"severity\":\"WARNING\","
                                + "\"message\":\"Solar gain reduced\"}"),
                Arguments.of(new TelemetryPayload.Photo(981, Camera.MASTCAM, 12, 7, Heading.EAST, "iVBORw0KGgoAAAA="),
                        "{\"type\":\"PHOTO\",\"commandId\":981,\"camera\":\"MASTCAM\",\"x\":12,\"y\":7,"
                                + "\"heading\":\"EAST\",\"imageBase64\":\"iVBORw0KGgoAAAA=\"}"));
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void payloadHasExactWireFormatAndRoundTrips(TelemetryPayload payload, String expectedJson) {
        String json = mapper.writeValueAsString(payload);

        assertThat(json).isEqualTo(expectedJson);
        assertThat(mapper.readValue(json, TelemetryPayload.class)).isEqualTo(payload);
        assertThat(expectedJson).startsWith("{\"type\":\"" + payload.type().name() + "\"");
    }

    @Test
    void everyTelemetryTypeHasExactlyOneMatchingRecord() {
        JsonSubTypes.Type[] subTypes = TelemetryPayload.class.getAnnotation(JsonSubTypes.class).value();

        assertThat(Arrays.stream(subTypes).map(JsonSubTypes.Type::name))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(TelemetryType.values()).map(Enum::name).toList());
        assertThat(TelemetryPayload.class.getPermittedSubclasses()).hasSameSizeAs(subTypes);
    }

    @Test
    void photoMetadataLeavesTheImageOut() {
        TelemetryPayload.Photo photo = new TelemetryPayload.Photo(981, Camera.NAVCAM, 1, 2, Heading.NORTH, "iVBORw0KGgo=");

        assertThat(mapper.writeValueAsString(photo.withoutImage()))
                .isEqualTo("{\"type\":\"PHOTO\",\"commandId\":981,\"camera\":\"NAVCAM\",\"x\":1,\"y\":2,\"heading\":\"NORTH\"}");
    }

    @Test
    void packetRoundTrips() {
        TelemetryPacket packet = new TelemetryPacket(UUID.randomUUID(), 1, Instant.parse("2026-10-26T12:00:10Z"),
                new TelemetryPayload.CommandStatus(981, CommandExecutionStatus.COMPLETED, 100, null, null));

        String json = mapper.writeValueAsString(packet);

        assertThat(mapper.readValue(json, TelemetryPacket.class)).isEqualTo(packet);
    }
}
