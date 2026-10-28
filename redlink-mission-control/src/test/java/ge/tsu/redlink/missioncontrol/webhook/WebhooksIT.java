package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.command.CommandService;
import ge.tsu.redlink.missioncontrol.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhooks against real PostgreSQL and a real HTTP stub receiver: registration and ownership,
 * fan-out in the transaction of the change, signed delivery, retries, 410, transitions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class WebhooksIT {

    private static final StubReceiver RECEIVER = new StubReceiver();

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    MutableClock clock;
    @Autowired
    WebhookDispatcher dispatcher;
    @Autowired
    TelemetryService telemetry;
    @Autowired
    CommandService commands;

    private String alice;
    private String bob;
    private long commandId;

    @BeforeEach
    void reset() {
        clock.set(T0);
        RECEIVER.reset();
        jdbc.sql("DELETE FROM webhooks").update();
        jdbc.sql("DELETE FROM telemetry").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("""
                UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL', last_telemetry_at = NULL
                WHERE id = 1""").update();
        alice = TestTokens.bearer(jwtEncoder, "OPERATOR", "alice");
        bob = TestTokens.bearer(jwtEncoder, "VIEWER", "bob");
        String location = commands.submit(1, new RoverCommand.Move(2), "alice", UUID.randomUUID().toString()).response().location();
        commandId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        jdbc.sql("UPDATE commands SET status = 'TRANSMITTED', sequence_no = 1 WHERE id = ?").param(commandId).update();
    }

    // --- registration --------------------------------------------------------------------------

    @Test
    void registrationShowsTheSecretOnceAndNeverAgain() throws Exception {
        String body = register(alice, RECEIVER.url(), "command.failed", "command.completed")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/webhooks/")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.secret").value(startsWith("whsec_")))
                .andExpect(jsonPath("$.events", contains("command.completed", "command.failed")))
                .andExpect(jsonPath("$._links.ping.method").value("POST"))
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(body).get("id").asString();

        mvc.perform(get("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(get("/api/v1/webhooks").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$[0].secret").doesNotExist());
    }

    @Test
    void webhooksArePrivateToTheirOwner() throws Exception {
        String id = id(register(alice, RECEIVER.url(), "command.completed"));

        mvc.perform(get("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/webhook-not-found"));
        mvc.perform(delete("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/webhooks").header(HttpHeaders.AUTHORIZATION, bob)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownEventsAreRefused() throws Exception {
        register(alice, RECEIVER.url(), "command.completed", "rover.exploded", "ping")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void numberOfWebhooksIsLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            register(alice, RECEIVER.url(), "command.completed").andExpect(status().isCreated());
        }
        register(alice, RECEIVER.url(), "command.completed")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/webhook-limit"));
    }

    @Test
    void deletedWebhookIsGoneWithItsPendingDeliveries() throws Exception {
        String id = id(register(alice, RECEIVER.url(), "command.completed"));
        report(CommandExecutionStatus.COMPLETED);

        mvc.perform(delete("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, alice)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, alice)).andExpect(status().isNotFound());
        assertThat(dispatcher.dispatchDue()).isZero();
    }

    // --- delivery ------------------------------------------------------------------------------

    @Test
    void completedCommandIsDeliveredSignedToSubscribersOnly() throws Exception {
        JsonNode subscriber = created(register(alice, RECEIVER.url(), "command.completed"));
        register(alice, RECEIVER.url(), "rover.safe_mode");

        report(CommandExecutionStatus.EXECUTING);
        report(CommandExecutionStatus.COMPLETED);
        assertThat(dispatcher.dispatchDue()).isEqualTo(1);

        StubReceiver.Received call = RECEIVER.received().get(0);
        PacketSigner verifier = new PacketSigner(subscriber.get("secret").asString().getBytes(StandardCharsets.UTF_8),
                clock, Duration.ofMinutes(5));
        assertThatCode(() -> verifier.verify(call.signature(), call.body())).doesNotThrowAnyException();
        assertThat(call.event()).isEqualTo("command.completed");
        assertThat(call.userAgent()).isEqualTo("RedLink-Webhooks/1");

        JsonNode body = jsonMapper.readTree(call.body());
        assertThat(body.get("event").asString()).isEqualTo("command.completed");
        assertThat(body.get("data").get("id").asLong()).isEqualTo(commandId);
        assertThat(body.get("data").get("status").asString()).isEqualTo("COMPLETED");

        mvc.perform(get("/api/v1/webhooks/" + subscriber.get("id").asString() + "/deliveries").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.content[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.content[0].lastStatusCode").value(204))
                .andExpect(jsonPath("$.content[0].id").value(call.deliveryId()));
    }

    @Test
    void failingReceiverIsRetriedWithBackoffThenGivenUp() throws Exception {
        String id = id(register(alice, RECEIVER.url(), "command.failed"));
        RECEIVER.respondWith(500, 500, 500);
        report(CommandExecutionStatus.FAILED);

        dispatcher.dispatchDue();
        assertThat(delivery(id)).containsEntry("status", "PENDING").containsEntry("last_status_code", 500);
        clock.advance(Duration.ofMillis(999));
        assertThat(dispatcher.dispatchDue()).as("backing off 1 s").isZero();
        clock.advance(Duration.ofMillis(1));
        dispatcher.dispatchDue();
        clock.advance(Duration.ofSeconds(2));
        dispatcher.dispatchDue();

        assertThat(delivery(id)).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat(RECEIVER.received()).hasSize(3);
        assertThat(RECEIVER.received()).extracting(StubReceiver.Received::deliveryId).containsOnly(RECEIVER.received().get(0).deliveryId());
    }

    @Test
    void goneReceiverDeactivatesTheWebhook() throws Exception {
        String id = id(register(alice, RECEIVER.url(), "command.completed", "command.failed"));
        RECEIVER.respondWith(410);
        report(CommandExecutionStatus.COMPLETED);
        dispatcher.dispatchDue();

        mvc.perform(get("/api/v1/webhooks/" + id).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.active").value(false));
        assertThat(delivery(id)).containsEntry("status", "FAILED").containsEntry("last_status_code", 410);
    }

    @Test
    void safeModeAndLowBatteryAreNotifiedOnTheTransitionOnly() throws Exception {
        String id = id(register(alice, RECEIVER.url(), "rover.safe_mode", "rover.battery_low"));

        state(RoverMode.NOMINAL, 50, 1);
        state(RoverMode.SAFE_MODE, 19, 2);   // enters safe mode and drops below 20 %
        state(RoverMode.SAFE_MODE, 18, 3);   // still there: nothing new

        assertThat(jdbc.sql("SELECT event FROM webhook_deliveries WHERE webhook_id = ? ORDER BY event")
                .param(UUID.fromString(id)).query(String.class).list())
                .containsExactly("rover.battery_low", "rover.safe_mode");
    }

    @Test
    void pingSendsATestDelivery() throws Exception {
        String id = id(register(bob, RECEIVER.url(), "command.completed"));

        mvc.perform(post("/api/v1/webhooks/" + id + "/ping").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isAccepted());
        dispatcher.dispatchDue();

        assertThat(RECEIVER.received()).extracting(StubReceiver.Received::event).containsExactly("ping");
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions register(String token, String url, String... events) throws Exception {
        return mvc.perform(post("/api/v1/webhooks").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("url", url, "events", events))));
    }

    private JsonNode created(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String id(ResultActions result) throws Exception {
        return created(result).get("id").asString();
    }

    private void report(CommandExecutionStatus status) {
        telemetry.receive(new TelemetryPacket(UUID.randomUUID(), 1, T0.plusSeconds(20),
                new TelemetryPayload.CommandStatus(commandId, status, status == CommandExecutionStatus.COMPLETED ? 100 : 50,
                        status == CommandExecutionStatus.FAILED ? "OBSTACLE" : null,
                        status == CommandExecutionStatus.FAILED ? "Rock at (32,30)" : null)));
    }

    private void state(RoverMode mode, double battery, int second) {
        telemetry.receive(new TelemetryPacket(UUID.randomUUID(), 1, T0.plusSeconds(second),
                new TelemetryPayload.RoverState(32, 32, Heading.NORTH, battery, -20, 0, mode)));
    }

    private Map<String, Object> delivery(String webhookId) {
        return jdbc.sql("SELECT status, attempts, last_status_code FROM webhook_deliveries WHERE webhook_id = ?")
                .param(UUID.fromString(webhookId)).query().singleRow();
    }
}
