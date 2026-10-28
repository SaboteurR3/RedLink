package ge.tsu.redlink.roversim.uplink;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.roversim.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The uplink against a real PostgreSQL: dedupe, conflicts and the telemetry outbox.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UplinkIT {

    private static final long ROVER = 1;

    @Autowired
    MockMvc mvc;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanLink() {
        jdbc.sql("DELETE FROM downlink_packets").update();
        jdbc.sql("DELETE FROM received_commands").update();
    }

    @Test
    void newPacketIsStoredAndReportedAfterOneLightDelay() throws Exception {
        CommandPacket packet = packet(UUID.randomUUID(), 981, 1, new RoverCommand.Move(5));

        mvc.perform(uplink(packet))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.packetId").value(packet.packetId().toString()))
                .andExpect(jsonPath("$.commandId").value(981))
                .andExpect(jsonPath("$.duplicate").value(false));

        Map<String, Object> stored = jdbc.sql("SELECT type, status, command::text AS command FROM received_commands WHERE packet_id = ?")
                .param(packet.packetId()).query().singleRow();
        assertThat(stored).containsEntry("type", "MOVE").containsEntry("status", "RECEIVED");
        assertThat(jsonMapper.readValue((String) stored.get("command"), RoverCommand.class)).isEqualTo(new RoverCommand.Move(5));

        Map<String, Object> telemetry = jdbc.sql("SELECT type, status, created_at, deliver_at, packet::text AS packet FROM downlink_packets")
                .query().singleRow();
        assertThat(telemetry).containsEntry("type", "COMMAND_STATUS").containsEntry("status", "PENDING");
        assertThat(Duration.between(instant(telemetry.get("created_at")), instant(telemetry.get("deliver_at"))))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat((String) telemetry.get("packet"))
                .contains("\"commandId\": 981").contains("\"status\": \"RECEIVED\"");
    }

    @Test
    void repeatedPacketTakesEffectOnce() throws Exception {
        CommandPacket packet = packet(UUID.randomUUID(), 982, 2, new RoverCommand.Rotate(Heading.EAST));

        String first = mvc.perform(uplink(packet)).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        mvc.perform(uplink(packet))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.receivedAt").value(jsonMapper.readTree(first).get("receivedAt").asString()));

        assertThat(count("received_commands")).isEqualTo(1);
        assertThat(count("downlink_packets")).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfOnePacketTakeEffectOnce() throws Exception {
        CommandPacket packet = packet(UUID.randomUUID(), 983, 3, new RoverCommand.TakePhoto(Camera.NAVCAM));
        int deliveries = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        try {
            for (int i = 0; i < deliveries; i++) {
                Callable<Integer> delivery = () -> {
                    start.await();
                    return mvc.perform(uplink(packet)).andReturn().getResponse().getStatus();
                };
                statuses.add(pool.submit(delivery));
            }
            start.countDown();
            for (Future<Integer> status : statuses) {
                assertThat(status.get()).isEqualTo(202);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("received_commands")).isEqualTo(1);
        assertThat(count("downlink_packets")).isEqualTo(1);
    }

    @Test
    void reusedPacketIdWithDifferentContentIsAConflict() throws Exception {
        UUID packetId = UUID.randomUUID();
        mvc.perform(uplink(packet(packetId, 984, 4, new RoverCommand.Move(5)))).andExpect(status().isAccepted());

        mvc.perform(uplink(packet(packetId, 984, 4, new RoverCommand.Move(15))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/packet-conflict"));
        assertThat(count("received_commands")).isEqualTo(1);
    }

    @Test
    void takenSequenceSlotIsAConflict() throws Exception {
        mvc.perform(uplink(packet(UUID.randomUUID(), 985, 5, new RoverCommand.Move(5)))).andExpect(status().isAccepted());

        mvc.perform(uplink(packet(UUID.randomUUID(), 986, 5, new RoverCommand.Move(5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/sequence-conflict"));
        assertThat(count("received_commands")).isEqualTo(1);
        assertThat(count("downlink_packets")).isEqualTo(1);
    }

    @Test
    void unknownRoverIsNotFound() throws Exception {
        CommandPacket packet = new CommandPacket(UUID.randomUUID(), 99, 987, 1, new RoverCommand.Reboot(), Instant.now());

        mvc.perform(uplink(packet))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/unknown-rover"));
        assertThat(count("received_commands")).isZero();
    }

    @Test
    void commandOutsideTheDictionaryLimitsIsRejected() throws Exception {
        mvc.perform(uplink(packet(UUID.randomUUID(), 988, 6, new RoverCommand.Move(0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-packet"))
                .andExpect(jsonPath("$.errors[0]").value(startsWith("command.distanceMeters")));
        assertThat(count("received_commands")).isZero();
    }

    @Test
    void unknownCommandTypeIsRejected() throws Exception {
        String json = """
                {"packetId":"%s","roverId":1,"commandId":989,"sequenceNo":7,
                 "command":{"type":"SELF_DESTRUCT"},"sentAt":"2026-10-26T12:00:00Z"}""".formatted(UUID.randomUUID());

        mvc.perform(signed(json.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-packet"))
                .andExpect(jsonPath("$.detail").value(not(containsString("SELF_DESTRUCT"))));
    }

    // --- helpers -------------------------------------------------------------------------------

    private static CommandPacket packet(UUID packetId, long commandId, long sequenceNo, RoverCommand command) {
        return new CommandPacket(packetId, ROVER, commandId, sequenceNo, command, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private MockHttpServletRequestBuilder uplink(CommandPacket packet) {
        return signed(jsonMapper.writeValueAsBytes(packet));
    }

    private MockHttpServletRequestBuilder signed(byte[] body) {
        return post("/flight/uplink")
                .contentType(MediaType.APPLICATION_JSON)
                .header(PacketSigner.HEADER, signer.sign(body))
                .content(body);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static Instant instant(Object timestamp) {
        return timestamp instanceof OffsetDateTime offset ? offset.toInstant() : ((Timestamp) timestamp).toInstant();
    }
}
