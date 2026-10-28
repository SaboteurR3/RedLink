package ge.tsu.redlink.missioncontrol.ratelimit;

import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
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

import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 429 with Retry-After once a user has used up the submission budget; other users are unaffected. */
@SpringBootTest(properties = {"redlink.rate-limit.submissions.capacity=2", "redlink.rate-limit.submissions.per=1m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RateLimitIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("DELETE FROM missions").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
    }

    @Test
    void aBurstIsFineThenTooManyRequests() throws Exception {
        String alice = TestTokens.bearer(jwtEncoder, "OPERATOR", "alice-" + UUID.randomUUID());
        submit(alice).andExpect(status().isAccepted());
        submit(alice).andExpect(status().isAccepted());
        submit(alice).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]?")))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rate-limited"))
                .andExpect(jsonPath("$.status").value(429));
        // Missions share the budget.
        mvc.perform(post("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, alice)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"m\",\"steps\":[{\"type\":\"ROTATE\",\"heading\":\"EAST\"}]}"))
                .andExpect(status().isTooManyRequests());

        String bob = TestTokens.bearer(jwtEncoder, "OPERATOR", "bob-" + UUID.randomUUID());
        submit(bob).andExpect(status().isAccepted());
    }

    private ResultActions submit(String token) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"MOVE\",\"distanceMeters\":1}"));
    }
}
