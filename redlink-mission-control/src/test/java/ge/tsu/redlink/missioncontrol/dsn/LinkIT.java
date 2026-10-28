package ge.tsu.redlink.missioncontrol.dsn;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.uplink.UplinkTransmitter;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Relay windows and solar conjunction against real PostgreSQL. Windows: 45 s every 2 min from the
 * Unix epoch, so T0 (12:00:00) opens one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "redlink.dsn.windows.enabled=true",
        "redlink.dsn.windows.every=2m",
        "redlink.dsn.windows.duration=45s",
        "redlink.dsn.conjunction-retry-after=30s"})
class LinkIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    UplinkTransmitter transmitter;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;

    private String operator;
    private String director;
    private String viewer;

    @BeforeEach
    void reset() {
        clock.set(T0);
        jdbc.sql("DELETE FROM rover_events").update();
        jdbc.sql("DELETE FROM telemetry").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE uplink_sequences SET last_sequence_no = 0").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
        endConjunction();
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR", "olga");
        director = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR", "dmitri");
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
    }

    /** Other test classes share the database and expect a clear link. */
    @AfterEach
    void endConjunction() {
        jdbc.sql("UPDATE link_state SET conjunction_active = FALSE, changed_by = NULL, changed_at = NULL, version = 0")
                .update();
    }

    // --- relay windows -------------------------------------------------------------------------

    @Test
    void commandsLeaveEarthOnlyWhileARelayWindowIsOpen() throws Exception {
        submitReboot();
        clock.advance(Duration.ofSeconds(5));
        assertThat(transmitter.transmitDue()).as("12:00:05, window open").isEqualTo(1);

        submitReboot();
        clock.set(T0.plusSeconds(50));
        assertThat(transmitter.transmitDue()).as("12:00:50, window closed").isZero();
        mvc.perform(get("/api/v1/link").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(jsonPath("$.oneWayDelaySeconds").value(10))
                .andExpect(jsonPath("$.queueHoldSeconds").value(5))
                .andExpect(jsonPath("$.window.enabled").value(true))
                .andExpect(jsonPath("$.window.open").value(false))
                .andExpect(jsonPath("$.window.nextChangeAt").value("2026-10-26T12:02:00Z"))
                .andExpect(jsonPath("$.uplinkOpen").value(false));

        clock.set(T0.plusSeconds(120));
        assertThat(transmitter.transmitDue()).as("12:02:00, next window").isEqualTo(1);
    }

    // --- conjunction resource ------------------------------------------------------------------

    @Test
    void flightDirectorStartsAConjunctionIdempotently() throws Exception {
        String tag = mvc.perform(get("/api/v1/link/conjunction").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        setConjunction(director, "{\"active\":true}", tag)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.changedBy").value("dmitri"))
                .andExpect(jsonPath("$.changedAt").value("2026-10-26T12:00:00Z"));
        setConjunction(director, "{\"active\":true}", null)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        mvc.perform(get("/api/v1/link/conjunction").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, "\"1\""))
                .andExpect(status().isNotModified());
        assertThat(jdbc.sql("SELECT count(*) FROM rover_events WHERE type = 'LINK'").query(Long.class).single())
                .as("one change, one event per rover").isEqualTo(roverCount());
    }

    @Test
    void conditionalAndRoleRules() throws Exception {
        setConjunction(director, "{\"active\":true}", "\"0\"").andExpect(status().isOk());

        setConjunction(director, "{\"active\":false}", "\"0\"")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/precondition-failed"));
        setConjunction(operator, "{\"active\":false}", null).andExpect(status().isForbidden());
        setConjunction(director, "{}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("active: must not be null"));
        mvc.perform(put("/api/v1/link/conjunction").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isUnauthorized());
        assertThat(conjunctionActive()).isTrue();
    }

    // --- conjunction effects -------------------------------------------------------------------

    @Test
    void duringAConjunctionCommandsWaitOnEarth() throws Exception {
        setConjunction(director, "{\"active\":true}", null);
        submitReboot();
        clock.advance(Duration.ofSeconds(10));

        assertThat(transmitter.transmitDue()).isZero();
        mvc.perform(get("/api/v1/link").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.window.open").value(true))
                .andExpect(jsonPath("$.conjunction.active").value(true))
                .andExpect(jsonPath("$.uplinkOpen").value(false));

        setConjunction(director, "{\"active\":false}", null);
        assertThat(transmitter.transmitDue()).isEqualTo(1);
    }

    @Test
    void duringAConjunctionTheDownlinkIs503WithRetryAfterAndNothingIsStored() throws Exception {
        setConjunction(director, "{\"active\":true}", null);
        TelemetryPacket packet = new TelemetryPacket(UUID.randomUUID(), 1, T0,
                new TelemetryPayload.RoverState(30, 30, Heading.EAST, 50, -20, 0, RoverMode.NOMINAL));

        downlink(packet)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/link-unavailable"));
        assertThat(jdbc.sql("SELECT count(*) FROM telemetry").query(Long.class).single()).isZero();

        setConjunction(director, "{\"active\":false}", null);
        downlink(packet).andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(false));
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions setConjunction(String token, String body, String ifMatch) throws Exception {
        var request = put("/api/v1/link/conjunction").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request);
    }

    private void submitReboot() throws Exception {
        mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"REBOOT\"}"))
                .andExpect(status().isAccepted());
    }

    private ResultActions downlink(TelemetryPacket packet) throws Exception {
        byte[] body = jsonMapper.writeValueAsBytes(packet);
        return mvc.perform(post("/dsn/downlink").contentType(MediaType.APPLICATION_JSON)
                .header(PacketSigner.HEADER, signer.sign(body)).content(body));
    }

    private boolean conjunctionActive() {
        return jdbc.sql("SELECT conjunction_active FROM link_state").query(Boolean.class).single();
    }

    private long roverCount() {
        return jdbc.sql("SELECT count(*) FROM rovers").query(Long.class).single();
    }
}
