package ge.tsu.redlink.missioncontrol.audit;

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
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit log against real PostgreSQL: written with each change (and only when it commits),
 * readable and filterable by flight directors, paged with RFC 8288 Link headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuditIT {

    private static final String MOVE_5 = "{\"type\":\"MOVE\",\"distanceMeters\":5}";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper jsonMapper;

    private String operator;
    private String director;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
        jdbc.sql("UPDATE link_state SET conjunction_active = FALSE, changed_by = NULL, changed_at = NULL, version = 0").update();
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR");
        director = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR");
    }

    @Test
    void changesAreRecordedNewestFirstWithWhoAndWhat() throws Exception {
        long id = submit();
        mvc.perform(post("/api/v1/commands/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/link/conjunction").header(HttpHeaders.AUTHORIZATION, director)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());

        audit("").andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].action").value("CONJUNCTION_DECLARED"))
                .andExpect(jsonPath("$.content[0].actor").value("flight_director"))
                .andExpect(jsonPath("$.content[0].target").value("link:conjunction"))
                .andExpect(jsonPath("$.content[1].action").value("COMMAND_CANCELLED"))
                .andExpect(jsonPath("$.content[2].action").value("COMMAND_SUBMITTED"))
                .andExpect(jsonPath("$.content[2].actor").value("operator"))
                .andExpect(jsonPath("$.content[2].target").value("command:" + id))
                .andExpect(jsonPath("$.content[2].detail").value(MOVE_5))
                .andExpect(jsonPath("$.content[2].occurredAt").exists());

        audit("?actor=operator").andExpect(jsonPath("$.page.totalElements").value(2));
        audit("?action=COMMAND_CANCELLED").andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void aRefusedChangeLeavesNoEntryAndAReplayNoSecond() throws Exception {
        jdbc.sql("UPDATE rovers SET last_mode = 'SAFE_MODE' WHERE id = 1").update();
        submitRaw(UUID.randomUUID().toString()).andExpect(status().isUnprocessableContent());
        jdbc.sql("UPDATE rovers SET last_mode = 'NOMINAL' WHERE id = 1").update();

        String key = UUID.randomUUID().toString();
        submitRaw(key).andExpect(status().isAccepted());
        submitRaw(key).andExpect(header().string("Idempotent-Replayed", "true"));

        audit("").andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void pagesCarryRfc8288LinkHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            submit();
        }
        audit("?size=1&actor=operator").andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.LINK,
                        "</api/v1/audit?size=1&actor=operator&page=0>; rel=\"first\", "
                                + "</api/v1/audit?size=1&actor=operator&page=1>; rel=\"next\", "
                                + "</api/v1/audit?size=1&actor=operator&page=2>; rel=\"last\""));
        audit("?size=1&actor=operator&page=1")
                .andExpect(header().string(HttpHeaders.LINK,
                        "</api/v1/audit?size=1&actor=operator&page=0>; rel=\"first\", "
                                + "</api/v1/audit?size=1&actor=operator&page=0>; rel=\"prev\", "
                                + "</api/v1/audit?size=1&actor=operator&page=2>; rel=\"next\", "
                                + "</api/v1/audit?size=1&actor=operator&page=2>; rel=\"last\""));
        // Every paged list gets them, e.g. the commands of a rover.
        mvc.perform(get("/api/v1/rovers/1/commands?size=2").header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(header().string(HttpHeaders.LINK,
                        "</api/v1/rovers/1/commands?size=2&page=0>; rel=\"first\", "
                                + "</api/v1/rovers/1/commands?size=2&page=1>; rel=\"next\", "
                                + "</api/v1/rovers/1/commands?size=2&page=1>; rel=\"last\""));
    }

    @Test
    void onlyFlightDirectorsReadIt() throws Exception {
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, operator)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
        audit("?action=NOPE").andExpect(status().isBadRequest());
    }

    private ResultActions audit(String query) throws Exception {
        return mvc.perform(get("/api/v1/audit" + query).header(HttpHeaders.AUTHORIZATION, director));
    }

    private long submit() throws Exception {
        String json = submitRaw(UUID.randomUUID().toString()).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(json).get("id").asLong();
    }

    private ResultActions submitRaw(String key) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(MOVE_5));
    }
}
