package ge.tsu.redlink.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    static Stream<Arguments> commands() {
        return Stream.of(
                Arguments.of(new RoverCommand.Move(5), "{\"type\":\"MOVE\",\"distanceMeters\":5}"),
                Arguments.of(new RoverCommand.Rotate(Heading.EAST), "{\"type\":\"ROTATE\",\"heading\":\"EAST\"}"),
                Arguments.of(new RoverCommand.TakePhoto(Camera.MASTCAM), "{\"type\":\"TAKE_PHOTO\",\"camera\":\"MASTCAM\"}"),
                Arguments.of(new RoverCommand.DrillSample(), "{\"type\":\"DRILL_SAMPLE\"}"),
                Arguments.of(new RoverCommand.Charge(30), "{\"type\":\"CHARGE\",\"minutes\":30}"),
                Arguments.of(new RoverCommand.Reboot(), "{\"type\":\"REBOOT\"}"));
    }

    @ParameterizedTest
    @MethodSource("commands")
    void commandHasExactWireFormatAndRoundTrips(RoverCommand command, String expectedJson) {
        String json = mapper.writeValueAsString(command);

        assertThat(json).isEqualTo(expectedJson);
        assertThat(mapper.readValue(json, RoverCommand.class)).isEqualTo(command);
    }

    @Test
    void everyCommandTypeHasExactlyOneMatchingRecord() {
        JsonSubTypes.Type[] subTypes = RoverCommand.class.getAnnotation(JsonSubTypes.class).value();

        assertThat(Arrays.stream(subTypes).map(JsonSubTypes.Type::name))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(CommandType.values()).map(Enum::name).toList());
        assertThat(RoverCommand.class.getPermittedSubclasses()).hasSameSizeAs(subTypes);
    }

    @ParameterizedTest
    @MethodSource("commands")
    void recordTypeMatchesItsJsonTypeId(RoverCommand command, String expectedJson) {
        assertThat(expectedJson).startsWith("{\"type\":\"" + command.type().name() + "\"");
    }

    @Test
    void unknownCommandTypeIsRejected() {
        assertThatThrownBy(() -> mapper.readValue("{\"type\":\"SELF_DESTRUCT\"}", RoverCommand.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void packetRoundTripsWithIsoTimestamp() {
        CommandPacket packet = new CommandPacket(
                UUID.fromString("7f3c1d2e-0000-4000-8000-0000000000e1"), 1, 981, 17,
                new RoverCommand.Move(5), Instant.parse("2026-10-26T12:00:00Z"));

        String json = mapper.writeValueAsString(packet);

        assertThat(json).isEqualTo("{\"packetId\":\"7f3c1d2e-0000-4000-8000-0000000000e1\",\"roverId\":1,"
                + "\"commandId\":981,\"sequenceNo\":17,\"command\":{\"type\":\"MOVE\",\"distanceMeters\":5},"
                + "\"sentAt\":\"2026-10-26T12:00:00Z\"}");
        assertThat(mapper.readValue(json, CommandPacket.class)).isEqualTo(packet);
    }
}
