package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production SSRF rules end to end: https only, public addresses only, at registration and
 * again at delivery.
 */
@SpringBootTest(properties = {"redlink.webhooks.allow-http=false", "redlink.webhooks.allow-private-hosts=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WebhookSsrfIT {

    private static final StubReceiver RECEIVER = new StubReceiver();

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebhookDispatcher dispatcher;

    @BeforeEach
    void reset() {
        RECEIVER.reset();
        jdbc.sql("DELETE FROM webhooks").update();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.example.com/hook",
            "https://localhost/hook",
            "https://127.0.0.1:8081/flight/uplink",
            "https://169.254.169.254/latest/meta-data/iam/",
            "https://[::1]/hook",
            "https://10.0.0.5/admin"})
    void urlsIntoOurOwnNetworkAreRefused(String url) throws Exception {
        mvc.perform(post("/api/v1/webhooks").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(jwtEncoder, "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"events\":[\"command.completed\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/validation-error"));
        assertThat(jdbc.sql("SELECT count(*) FROM webhooks").query(Long.class).single()).isZero();
    }

    /**
     * A webhook whose URL became unsafe after registration (e.g. its DNS now points inside) is
     * re-checked before every delivery and never called.
     */
    @Test
    void deliveryRechecksTheUrl() {
        UUID webhook = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.sql("INSERT INTO webhooks (id, owner, url, secret, events, active, created_at) "
                        + "VALUES (?, 'mallory', ?, 'whsec_test-secret-of-at-least-32-bytes!!', ARRAY['command.completed'], TRUE, ?)")
                .params(webhook, RECEIVER.url(), now).update();
        jdbc.sql("INSERT INTO webhook_deliveries (id, webhook_id, event, payload, next_attempt_at, created_at) "
                        + "VALUES (gen_random_uuid(), ?, 'command.completed', '{}', ?, ?)")
                .params(webhook, now, now).update();

        dispatcher.dispatchDue();

        assertThat(RECEIVER.received()).isEmpty();
        assertThat(jdbc.sql("SELECT last_error FROM webhook_deliveries WHERE webhook_id = ?").param(webhook)
                .query(String.class).single()).startsWith("URL refused");
    }
}
