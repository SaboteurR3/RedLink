package ge.tsu.redlink.contract;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.TraceContext;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ContractValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        // ParameterMessageInterpolator avoids needing an Expression Language implementation in tests.
        factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void moveAcceptsDistanceWithinLimits(int distance) {
        assertThat(validator.validate(new RoverCommand.Move(distance))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 21})
    void moveRejectsDistanceOutsideLimits(int distance) {
        assertThat(violatedProperties(validator.validate(new RoverCommand.Move(distance))))
                .containsExactly("distanceMeters");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 121})
    void chargeRejectsMinutesOutsideLimits(int minutes) {
        assertThat(violatedProperties(validator.validate(new RoverCommand.Charge(minutes))))
                .containsExactly("minutes");
    }

    @Test
    void rotateRequiresHeading() {
        assertThat(violatedProperties(validator.validate(new RoverCommand.Rotate(null))))
                .containsExactly("heading");
    }

    @Test
    void packetValidatesNestedCommand() {
        CommandPacket packet = new CommandPacket(UUID.randomUUID(), 1, 1, 1, new RoverCommand.Move(0), Instant.now());

        assertThat(violatedProperties(validator.validate(packet))).containsExactly("command.distanceMeters");
    }

    @Test
    void traceparentMustBeW3cVersion00() {
        String valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        assertThat(validator.validate(packetTracedAs(valid))).isEmpty();
        for (String invalid : List.of(
                "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // unknown version
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // all-zero trace id
                "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // all-zero parent id
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", // uppercase
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01; rm -rf")) {
            assertThat(violatedProperties(validator.validate(packetTracedAs(invalid)))).containsExactly("traceparent");
        }
        assertThat(TraceContext.traceId(valid)).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private static CommandPacket packetTracedAs(String traceparent) {
        return new CommandPacket(UUID.randomUUID(), 1, 1, 1, new RoverCommand.Move(1), Instant.now(), traceparent);
    }

    @Test
    void packetRejectsMissingFieldsAndNonPositiveIds() {
        CommandPacket packet = new CommandPacket(null, 0, 0, 0, null, null);

        assertThat(violatedProperties(validator.validate(packet)))
                .containsExactlyInAnyOrder("packetId", "roverId", "commandId", "sequenceNo", "command", "sentAt");
    }

    @Test
    void roverStateRejectsBatteryAbove100Percent() {
        var state = new TelemetryPayload.RoverState(0, 0, Heading.NORTH, 100.1, -20, 0, RoverMode.NOMINAL);

        assertThat(violatedProperties(validator.validate(state))).containsExactly("batteryPercent");
    }

    private static <T> Set<String> violatedProperties(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
