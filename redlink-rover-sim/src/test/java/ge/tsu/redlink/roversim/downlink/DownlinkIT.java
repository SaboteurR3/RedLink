package ge.tsu.redlink.roversim.downlink;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.roversim.MutableClock;
import ge.tsu.redlink.roversim.MutableClockConfiguration;
import ge.tsu.redlink.roversim.TestcontainersConfiguration;
import ge.tsu.redlink.roversim.downlink.DownlinkClaims.ClaimedPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static ge.tsu.redlink.roversim.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Telemetry delivery against real PostgreSQL and a real HTTP stub Earth, with a hand-moved clock.
 * Every packet is queued at T0, so it is due at T0 + 10 s (the one-way light time).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class DownlinkIT {

    private static final StubEarth EARTH = new StubEarth();
    private static final Instant DUE = T0.plusSeconds(10);

    @DynamicPropertySource
    static void earth(DynamicPropertyRegistry registry) {
        registry.add("redlink.downlink.earth-url", EARTH::url);
    }

    @Autowired
    DownlinkDispatcher dispatcher;
    @Autowired
    DownlinkClaims claims;
    @Autowired
    TelemetryOutbox outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    MutableClock clock;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        clock.set(T0);
        EARTH.reset();
        jdbc.sql("DELETE FROM downlink_packets").update();
    }

    @Test
    void packetLeavesOnlyAfterItsLightTimeAndArrivesSigned() {
        TelemetryPacket packet = queue();

        clock.set(DUE.minusMillis(1));
        assertThat(dispatcher.dispatchDue()).isZero();
        assertThat(EARTH.received()).isEmpty();

        clock.set(DUE);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        StubEarth.Received received = EARTH.received().get(0);
        assertThatCode(() -> signer.verify(received.signature(), received.body())).doesNotThrowAnyException();
        assertThat(jsonMapper.readValue(received.body(), TelemetryPacket.class)).isEqualTo(packet);
        assertThat(received.upgrade()).as("plain HTTP/1.1, no h2c upgrade").isNull();
        assertThat(row(packet.packetId())).containsEntry("status", "SENT").containsEntry("attempts", 1);
    }

    @Test
    void serverErrorsAreRetriedWithExponentialBackoff() {
        TelemetryPacket packet = queue();
        EARTH.respondWith(503, 503);

        clock.set(DUE);
        dispatcher.dispatchDue();
        assertThat(row(packet.packetId())).containsEntry("status", "PENDING").containsEntry("last_error", "HTTP 503");
        assertThat(nextAttempt(packet.packetId())).isEqualTo(DUE.plusSeconds(1));

        clock.set(DUE.plusMillis(999));
        assertThat(dispatcher.dispatchDue()).as("still backing off").isZero();

        clock.set(DUE.plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(nextAttempt(packet.packetId())).isEqualTo(DUE.plusSeconds(1 + 2));

        clock.set(DUE.plusSeconds(3));
        dispatcher.dispatchDue();
        assertThat(row(packet.packetId())).containsEntry("status", "SENT").containsEntry("attempts", 3);
        assertThat(EARTH.received()).hasSize(3);
    }

    @Test
    void retryAfterFromEarthIsHonouredAndTheRestOfTheBatchWaitsToo() {
        TelemetryPacket first = queue();
        TelemetryPacket second = queue();
        EARTH.respondWith(503, "30"); // solar conjunction: try again in 30 s

        clock.set(DUE);
        assertThat(dispatcher.dispatchDue()).isEqualTo(2);

        assertThat(EARTH.received()).as("the second packet was not even tried").hasSize(1);
        assertThat(nextAttempt(first.packetId())).as("30 s, not the 1 s backoff").isEqualTo(DUE.plusSeconds(30));
        assertThat(nextAttempt(second.packetId())).isEqualTo(DUE.plusSeconds(30));
        assertThat(row(second.packetId())).containsEntry("status", "PENDING");

        clock.set(DUE.plusSeconds(30));
        dispatcher.dispatchDue();
        assertThat(row(first.packetId())).containsEntry("status", "SENT");
        assertThat(row(second.packetId())).containsEntry("status", "SENT");
    }

    @Test
    void unreachableEarthIsRetried() {
        TelemetryPacket packet = queue();
        EARTH.respondWith(StubEarth.DROP_CONNECTION);

        clock.set(DUE);
        dispatcher.dispatchDue();

        assertThat(row(packet.packetId())).containsEntry("status", "PENDING");
        assertThat((String) row(packet.packetId()).get("last_error")).isNotBlank();
        assertThat(nextAttempt(packet.packetId())).isEqualTo(DUE.plusSeconds(1));
    }

    @Test
    void missingEndpointIsRetriedBecauseEarthMayStillBeDeploying() {
        TelemetryPacket packet = queue();
        EARTH.respondWith(404);

        clock.set(DUE);
        dispatcher.dispatchDue();

        assertThat(row(packet.packetId())).containsEntry("status", "PENDING");
    }

    @Test
    void packetEarthRejectsIsNeverResent() {
        TelemetryPacket packet = queue();
        EARTH.respondWith(400);

        clock.set(DUE);
        dispatcher.dispatchDue();
        clock.set(DUE.plus(Duration.ofHours(1)));
        assertThat(dispatcher.dispatchDue()).isZero();

        assertThat(row(packet.packetId())).containsEntry("status", "REJECTED").containsEntry("last_error", "HTTP 400");
        assertThat(EARTH.received()).hasSize(1);
    }

    @Test
    void concurrentDispatchersNeverClaimTheSamePacket() throws Exception {
        for (int i = 0; i < 40; i++) {
            queue();
        }
        clock.set(DUE);
        int dispatchers = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<ClaimedPacket>>> results = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(dispatchers);
        try {
            for (int i = 0; i < dispatchers; i++) {
                Callable<List<ClaimedPacket>> claim = () -> {
                    start.await();
                    return claims.claimDue(DUE, DUE.plusSeconds(30), 15);
                };
                results.add(pool.submit(claim));
            }
            start.countDown();

            Set<UUID> claimed = new HashSet<>();
            int total = 0;
            for (Future<List<ClaimedPacket>> result : results) {
                for (ClaimedPacket packet : result.get()) {
                    claimed.add(packet.packetId());
                    total++;
                }
            }
            assertThat(total).as("claims overlap").isEqualTo(claimed.size());
            assertThat(claimed).hasSize(40);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void packetOfACrashedDispatcherIsSentAfterItsLeaseExpires() {
        TelemetryPacket packet = queue();
        claims.claimDue(DUE, DUE.plusSeconds(30), 10); // claimed, then the "dispatcher" dies

        clock.set(DUE.plusSeconds(29));
        assertThat(dispatcher.dispatchDue()).isZero();

        clock.set(DUE.plusSeconds(30));
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);
        assertThat(row(packet.packetId())).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    // --- helpers -------------------------------------------------------------------------------

    private TelemetryPacket queue() {
        return tx.execute(status -> outbox.emit(1, new TelemetryPayload.CommandStatus(
                42, CommandExecutionStatus.RECEIVED, 0, null, null)));
    }

    private Map<String, Object> row(UUID packetId) {
        return jdbc.sql("SELECT status, attempts, last_error FROM downlink_packets WHERE packet_id = ?")
                .param(packetId).query().singleRow();
    }

    private Instant nextAttempt(UUID packetId) {
        return jdbc.sql("SELECT next_attempt_at FROM downlink_packets WHERE packet_id = ?")
                .param(packetId).query(java.time.OffsetDateTime.class).single().toInstant();
    }
}
