package ge.tsu.redlink.missioncontrol.web;

import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests from the QA pass (ticket 22): request bodies are read strictly and every error,
 * including Spring's own, is a problem with a stable type and a field-level reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ApiErrorsIT {

    private static final String PROBLEMS = "https://redlink.example/problems/";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;

    private String operator;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM rover_events").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR");
    }

    // --- no silent coercion (a 1.5 m drive must not become 1 m) -----------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"type\":\"MOVE\",\"distanceMeters\":1.5}       | distanceMeters: must be a whole number",
            "{\"type\":\"MOVE\",\"distanceMeters\":\"3\"}     | distanceMeters: must be a whole number",
            "{\"type\":\"CHARGE\",\"minutes\":2.0}            | minutes: must be a whole number",
            "{\"type\":\"ROTATE\",\"heading\":1}              | heading: must be one of NORTH, EAST, SOUTH, WEST",
            "{\"type\":\"ROTATE\",\"heading\":\"UP\"}         | heading: must be one of NORTH, EAST, SOUTH, WEST",
            "{\"type\":\"TAKE_PHOTO\",\"camera\":\"HAZCAM\"}  | camera: must be one of NAVCAM, MASTCAM"})
    void wrongValuesAreRefusedWithTheFieldAndWhatIsAllowed(String body, String error) throws Exception {
        submit(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"))
                .andExpect(jsonPath("$.errors[0]").value(error));
        assertThat(jdbc.sql("SELECT count(*) FROM commands").query(Long.class).single()).isZero();
    }

    @Test
    void unknownOrMissingCommandTypeListsTheDictionary() throws Exception {
        submit("{\"type\":\"SELF_DESTRUCT\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]")
                        .value("type: 'SELF_DESTRUCT' is not one of MOVE, ROTATE, TAKE_PHOTO, DRILL_SAMPLE, CHARGE, REBOOT"));
        submit("{\"distanceMeters\":3}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]")
                        .value("type: is missing; one of MOVE, ROTATE, TAKE_PHOTO, DRILL_SAMPLE, CHARGE, REBOOT"));
    }

    @Test
    void anUnknownTypeIdThatIsNotAPlainWordIsNotEchoed() throws Exception {
        submit("{\"type\":\"<img src=x onerror=alert(1)>\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("type: is not one of")));
    }

    @Test
    void missionStepsAreNamedByPosition() throws Exception {
        mvc.perform(post("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"steps\":[{\"type\":\"REBOOT\"},{\"type\":\"ROTATE\",\"heading\":\"up\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("steps[1].heading: must be one of NORTH, EAST, SOUTH, WEST"));
    }

    @Test
    void plainBadJsonStillGetsAProblemWithoutParserDetails() throws Exception {
        submit("{\"type\": MOVE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "malformed-request"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    // --- Spring's own errors have stable types too ------------------------------------------------

    @Test
    void builtInErrorsCarryAType() throws Exception {
        mvc.perform(delete("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("GET"), org.hamcrest.Matchers.containsString("PATCH"))))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "method-not-allowed"));
        mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, operator).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "not-acceptable"));
        mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.TEXT_PLAIN).content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "unsupported-media-type"));
    }

    @Test
    void wrongMethodOnTheLoginEndpointIs405NotAMisleading401() throws Exception {
        mvc.perform(get("/api/v1/auth/token"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "method-not-allowed"));
    }

    private ResultActions submit(String body) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
