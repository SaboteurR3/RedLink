package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.uplink.UplinkTransmitter;
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

import java.time.Duration;
import java.util.UUID;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approval workflow over HTTP against real PostgreSQL: roles, four-eyes rule, decisions,
 * links, and the hold after approval.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class ApprovalsIT {

    private static final String DRILL = "{\"type\":\"DRILL_SAMPLE\"}";

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

    private String operator;
    private String director;
    private String otherDirector;

    @BeforeEach
    void reset() {
        clock.set(T0);
        jdbc.sql("DELETE FROM rover_events").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE uplink_sequences SET last_sequence_no = 0").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR", "olga");
        director = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR", "dmitri");
        otherDirector = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR", "dana");
    }

    @Test
    void drillWaitsForApprovalAndSaysHowToDecide() throws Exception {
        submit(operator, DRILL)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.approval.state").value("PENDING"))
                .andExpect(jsonPath("$._links.approve.method").value("POST"))
                .andExpect(jsonPath("$._links.reject.href").exists())
                .andExpect(jsonPath("$._links.cancel").exists());
    }

    @Test
    void anotherFlightDirectorApproves() throws Exception {
        long id = drillBy(operator);

        decide(director, id, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.approval.state").value("APPROVED"))
                .andExpect(jsonPath("$.approval.decidedBy").value("dmitri"))
                .andExpect(jsonPath("$._links.approve").doesNotExist())
                .andExpect(jsonPath("$._links.cancel").exists());
        assertThat(jdbc.sql("SELECT count(*) FROM rover_events WHERE type = 'COMMAND' AND data LIKE '%APPROVED%'")
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void operatorsCannotApprove() throws Exception {
        long id = drillBy(operator);

        decide(operator, id, "approve")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/forbidden"));
    }

    @Test
    void nobodyApprovesTheirOwnCommand() throws Exception {
        long id = drillBy(director);

        decide(director, id, "approve")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/four-eyes-violation"));
        decide(otherDirector, id, "approve").andExpect(status().isOk());
    }

    @Test
    void approvedDrillWaitsTheFullHoldCountedFromTheApproval() throws Exception {
        long id = drillBy(operator);
        clock.set(T0.plus(Duration.ofHours(1)));
        decide(director, id, "approve").andExpect(status().isOk());

        clock.advance(Duration.ofMillis(4999));
        assertThat(transmitter.transmitDue()).as("still in the cancel window").isZero();

        clock.advance(Duration.ofMillis(1));
        assertThat(transmitter.transmitDue()).isEqualTo(1);
    }

    @Test
    void rejectionIsFinalAndKeepsTheReason() throws Exception {
        long id = drillBy(operator);

        mvc.perform(post("/api/v1/commands/" + id + "/reject").header(HttpHeaders.AUTHORIZATION, director)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Battery margin too thin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.approval.reason").value("Battery margin too thin"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$._links.cancel").doesNotExist());
    }

    @Test
    void rejectionNeedsNoReasonButLimitsIt() throws Exception {
        long id = drillBy(operator);

        mvc.perform(post("/api/v1/commands/" + id + "/reject").header(HttpHeaders.AUTHORIZATION, director)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        decide(director, id, "reject").andExpect(status().isOk());
    }

    @Test
    void repeatingADecisionIsSafeContradictingItIsAConflict() throws Exception {
        long approved = drillBy(operator);
        decide(director, approved, "approve").andExpect(status().isOk());

        decide(director, approved, "approve").andExpect(status().isOk());
        decide(director, approved, "reject")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/command-not-pending-approval"));

        long cancelled = drillBy(operator);
        mvc.perform(post("/api/v1/commands/" + cancelled + "/cancel").header(HttpHeaders.AUTHORIZATION, operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval.state").value("WITHDRAWN"));
        decide(director, cancelled, "approve").andExpect(status().isConflict());
    }

    @Test
    void drillingNeedsThirtyPercentBatteryWhenAskedAndWhenApproved() throws Exception {
        long id = drillBy(operator);
        jdbc.sql("UPDATE rovers SET last_battery_percent = 25 WHERE id = 1").update();

        decide(director, id, "approve")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/insufficient-battery"));
        submit(operator, DRILL).andExpect(status().isUnprocessableContent());
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions submit(String token, String command) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(command));
    }

    private long drillBy(String token) throws Exception {
        String location = submit(token, DRILL).andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private ResultActions decide(String token, long commandId, String decision) throws Exception {
        return mvc.perform(post("/api/v1/commands/" + commandId + "/" + decision).header(HttpHeaders.AUTHORIZATION, token));
    }
}
