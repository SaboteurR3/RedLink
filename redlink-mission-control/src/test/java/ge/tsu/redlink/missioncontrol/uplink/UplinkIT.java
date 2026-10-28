package ge.tsu.redlink.missioncontrol.uplink;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.link.TraceContext;
import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.command.CommandNotCancellableException;
import ge.tsu.redlink.missioncontrol.command.CommandService;
import ge.tsu.redlink.missioncontrol.tracing.LinkTracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Commands leaving Earth: hold, sequence numbers, transmission, delayed signed delivery to a real
 * HTTP stub rover. Real PostgreSQL, hand-moved clock. Commands are created at T0, transmitted at
 * T0 + 5 s (hold) and arrive at T0 + 15 s (10 s light time).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class UplinkIT {

    private static final StubRover ROVER = new StubRover();
    private static final Instant TRANSMIT = T0.plusSeconds(5);
    private static final Instant ARRIVE = T0.plusSeconds(15);

    @DynamicPropertySource
    static void rover(DynamicPropertyRegistry registry) {
        registry.add("redlink.uplink.rover-url", ROVER::url);
    }

    @Autowired
    CommandService commands;
    @Autowired
    UplinkTransmitter transmitter;
    @Autowired
    UplinkDispatcher dispatcher;
    @Autowired
    MutableClock clock;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LinkTracing tracing;

    @BeforeEach
    void reset() {
        clock.set(T0);
        ROVER.reset();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE uplink_sequences SET last_sequence_no = 0").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
    }

    // --- transmission --------------------------------------------------------------------------

    @Test
    void queuedCommandIsHeldThenTransmittedAndCannotBeCancelledAnyMore() {
        long id = submit(new RoverCommand.Move(3));

        clock.set(TRANSMIT.minusMillis(1));
        assertThat(transmitter.transmitDue()).isZero();
        assertThat(column(id, "status")).isEqualTo("QUEUED");

        clock.set(TRANSMIT);
        assertThat(transmitter.transmitDue()).isEqualTo(1);

        assertThat(column(id, "status")).isEqualTo("TRANSMITTED");
        assertThat(column(id, "sequence_no")).isEqualTo("1");
        assertThat(packet(id)).containsEntry("status", "PENDING");
        assertThat(instant(packet(id).get("deliver_at"))).isEqualTo(ARRIVE);
        assertThatThrownBy(() -> commands.cancel(id, "operator")).isInstanceOf(CommandNotCancellableException.class);
    }

    @Test
    void sequenceNumbersFollowCreationOrderAndCancelledCommandsLeaveNoGap() {
        long first = submit(new RoverCommand.Move(1));
        long cancelled = submit(new RoverCommand.Move(2));
        long third = submit(new RoverCommand.Move(3));
        commands.cancel(cancelled, "operator");

        clock.set(TRANSMIT);
        assertThat(transmitter.transmitDue()).isEqualTo(2);

        assertThat(column(first, "sequence_no")).isEqualTo("1");
        assertThat(column(third, "sequence_no")).isEqualTo("2");
        assertThat(column(cancelled, "sequence_no")).isNull();
        assertThat(column(cancelled, "status")).isEqualTo("CANCELLED");
    }

    @Test
    void commandsWaitingForApprovalAreNeverTransmitted() {
        long drill = submit(new RoverCommand.DrillSample());

        clock.set(T0.plus(Duration.ofHours(1)));

        assertThat(transmitter.transmitDue()).isZero();
        assertThat(column(drill, "status")).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void concurrentTransmittersKeepSequenceOrderEqualToCreationOrder() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ids.add(submit(new RoverCommand.Move(1)));
        }
        clock.set(TRANSMIT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            for (int t = 0; t < 3; t++) {
                Callable<Integer> drain = () -> {
                    start.await();
                    int total = 0;
                    for (int round = 0; round < 20; round++) {
                        total += transmitter.transmitDue();
                    }
                    return total;
                };
                results.add(pool.submit(drain));
            }
            start.countDown();
            int transmitted = 0;
            for (Future<Integer> result : results) {
                transmitted += result.get();
            }
            assertThat(transmitted).isEqualTo(45);
        } finally {
            pool.shutdownNow();
        }

        List<Long> sequenceByCreation = jdbc.sql("SELECT sequence_no FROM commands ORDER BY id").query(Long.class).list();
        assertThat(sequenceByCreation).isSorted().doesNotHaveDuplicates().hasSize(45);
        assertThat(sequenceByCreation.get(0)).isEqualTo(1);
        assertThat(sequenceByCreation.get(44)).isEqualTo(45);
        assertThat(jdbc.sql("SELECT count(*) FROM uplink_packets").query(Long.class).single()).isEqualTo(45);
    }

    // --- tracing (ticket 33) --------------------------------------------------------------------

    @Test
    void theOperatorsTraceTravelsWithTheCommandToTheRover() {
        // The operator's request, as the HTTP server span would be.
        String[] requestTrace = new String[1];
        long id = tracing.inSpan(null, "operator request", () -> {
            requestTrace[0] = tracing.currentTraceparent();
            return submit(new RoverCommand.Move(3));
        });
        assertThat(requestTrace[0]).isNotNull();
        String traceId = TraceContext.traceId(requestTrace[0]);
        assertThat(column(id, "trace_parent")).isEqualTo(requestTrace[0]);

        // Transmitted and sent later by schedulers, outside that request: still the same trace.
        clock.set(TRANSMIT);
        transmitter.transmitDue();
        clock.set(ARRIVE);
        dispatcher.dispatchDue();

        StubRover.Received received = ROVER.received().get(0);
        CommandPacket packet = jsonMapper.readValue(received.body(), CommandPacket.class);
        assertThat(packet.traceparent()).isEqualTo(requestTrace[0]);
        assertThat(TraceContext.traceId(received.traceparent())).isEqualTo(traceId);
        // The header names the "uplink send" span, a child in the same trace.
        assertThat(received.traceparent()).isNotEqualTo(requestTrace[0]);
    }

    @Test
    void anUntracedCommandSendsNoTraceContext() {
        submit(new RoverCommand.Move(3));
        clock.set(TRANSMIT);
        transmitter.transmitDue();
        clock.set(ARRIVE);
        dispatcher.dispatchDue();

        StubRover.Received received = ROVER.received().get(0);
        assertThat(new String(received.body(), StandardCharsets.UTF_8)).doesNotContain("traceparent");
        // The send itself is a span (a new trace), so the rover still gets a header.
        assertThat(received.traceparent()).matches(TraceContext.TRACEPARENT_PATTERN);
    }

    // --- delivery ------------------------------------------------------------------------------

    @Test
    void packetReachesTheRoverAfterOneLightTimeSigned() {
        long id = submit(new RoverCommand.Move(3));
        clock.set(TRANSMIT);
        transmitter.transmitDue();

        clock.set(ARRIVE.minusMillis(1));
        assertThat(dispatcher.dispatchDue()).isZero();

        clock.set(ARRIVE);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        StubRover.Received received = ROVER.received().get(0);
        assertThatCode(() -> signer.verify(received.signature(), received.body())).doesNotThrowAnyException();
        assertThat(received.upgrade()).isNull();
        CommandPacket packet = jsonMapper.readValue(received.body(), CommandPacket.class);
        assertThat(packet.commandId()).isEqualTo(id);
        assertThat(packet.roverId()).isEqualTo(1);
        assertThat(packet.sequenceNo()).isEqualTo(1);
        assertThat(packet.command()).isEqualTo(new RoverCommand.Move(3));
        assertThat(packet.sentAt()).isEqualTo(TRANSMIT);

        assertThat(packet(id)).containsEntry("status", "SENT");
        // Reaching Mars is learned from telemetry (ticket 11), not from the HTTP answer.
        assertThat(column(id, "status")).isEqualTo("TRANSMITTED");
    }

    @Test
    void unreachableRoverIsRetriedWithBackoff() {
        long id = submit(new RoverCommand.Move(3));
        ROVER.respondWith(503, 503);
        clock.set(TRANSMIT);
        transmitter.transmitDue();

        clock.set(ARRIVE);
        dispatcher.dispatchDue();
        assertThat(packet(id)).containsEntry("status", "PENDING").containsEntry("last_error", "HTTP 503");
        assertThat(instant(packet(id).get("next_attempt_at"))).isEqualTo(ARRIVE.plusSeconds(1));

        clock.set(ARRIVE.plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(instant(packet(id).get("next_attempt_at"))).isEqualTo(ARRIVE.plusSeconds(3));

        clock.set(ARRIVE.plusSeconds(3));
        dispatcher.dispatchDue();
        assertThat(packet(id)).containsEntry("status", "SENT").containsEntry("attempts", 3);
    }

    @Test
    void packetTheRoverRefusesForGoodFailsTheCommand() {
        long id = submit(new RoverCommand.Move(3));
        ROVER.respondWith(409);
        clock.set(TRANSMIT);
        transmitter.transmitDue();

        clock.set(ARRIVE);
        dispatcher.dispatchDue();
        clock.set(ARRIVE.plus(Duration.ofHours(1)));
        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(packet(id)).containsEntry("status", "REJECTED");
        assertThat(column(id, "status")).isEqualTo("FAILED");
        assertThat(column(id, "failure_code")).isEqualTo("UPLINK_REJECTED");
        assertThat(ROVER.received()).hasSize(1);
    }

    // --- helpers -------------------------------------------------------------------------------

    private long submit(RoverCommand command) {
        String location = commands.submit(1, command, "operator", UUID.randomUUID().toString()).response().location();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    /** One column as text; {@code null} for SQL NULL. */
    private String column(long commandId, String column) {
        return (String) jdbc.sql("SELECT " + column + "::text AS value FROM commands WHERE id = ?").param(commandId)
                .query().singleRow().get("value");
    }

    private Map<String, Object> packet(long commandId) {
        return jdbc.sql("SELECT status, attempts, last_error, deliver_at, next_attempt_at FROM uplink_packets WHERE command_id = ?")
                .param(commandId).query().singleRow();
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime offset ? offset.toInstant() : ((java.sql.Timestamp) timestamp).toInstant();
    }
}
