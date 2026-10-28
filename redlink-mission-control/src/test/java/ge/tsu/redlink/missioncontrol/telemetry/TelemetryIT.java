package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.EventSeverity;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.command.CommandService;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Telemetry arriving on {@code /dsn/downlink} (signed, over HTTP) against real PostgreSQL: dedupe,
 * forward-only command lifecycle, last known rover state, history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TelemetryIT {

    private static final Instant MARS_T = Instant.parse("2026-10-26T12:00:10Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    CommandService commands;
    @Autowired
    JwtEncoder jwtEncoder;

    private long commandId;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM telemetry").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("""
                UPDATE rovers SET last_x = 32, last_y = 32, last_heading = 'NORTH', last_battery_percent = 100,
                       last_mode = 'NOMINAL', last_telemetry_at = NULL WHERE id = 1""").update();
        String location = commands.submit(1, new RoverCommand.Move(3), "operator", UUID.randomUUID().toString())
                .response().location();
        commandId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
        jdbc.sql("UPDATE commands SET status = 'TRANSMITTED', sequence_no = 1 WHERE id = ?").param(commandId).update();
    }

    // --- command lifecycle ---------------------------------------------------------------------

    @Test
    void roverReportsDriveTheCommandToCompleted() throws Exception {
        send(report(CommandExecutionStatus.RECEIVED, 0), MARS_T).andExpect(status().isAccepted());
        assertThat(command()).containsEntry("status", "RECEIVED");

        send(report(CommandExecutionStatus.EXECUTING, 33), MARS_T.plusSeconds(1));
        assertThat(command()).containsEntry("status", "EXECUTING").containsEntry("progress_percent", 33);

        send(report(CommandExecutionStatus.COMPLETED, 100), MARS_T.plusSeconds(3));
        assertThat(command()).containsEntry("status", "COMPLETED").containsEntry("progress_percent", 100);

        mvc.perform(get("/api/v1/commands/" + commandId).header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(jwtEncoder, "VIEWER")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finishedAt").value("2026-10-26T12:00:13Z"));
    }

    @Test
    void lateReportsAreStoredButChangeNothing() throws Exception {
        send(report(CommandExecutionStatus.COMPLETED, 100), MARS_T.plusSeconds(3));

        send(report(CommandExecutionStatus.EXECUTING, 33), MARS_T.plusSeconds(1)).andExpect(status().isAccepted());

        assertThat(command()).containsEntry("status", "COMPLETED");
        assertThat(telemetryCount()).isEqualTo(2);
    }

    @Test
    void failureCarriesTheRoversReason() throws Exception {
        send(new TelemetryPayload.CommandStatus(commandId, CommandExecutionStatus.FAILED, 33, "OBSTACLE", "Rock at (32,30)"),
                MARS_T);

        assertThat(command()).containsEntry("status", "FAILED").containsEntry("failure_code", "OBSTACLE")
                .containsEntry("failure_detail", "Rock at (32,30)");
    }

    @Test
    void refusalBecomesRefusedByRover() throws Exception {
        send(new TelemetryPayload.CommandStatus(commandId, CommandExecutionStatus.REFUSED, 0, "SAFE_MODE",
                "Rover is in safe mode; only REBOOT is accepted"), MARS_T);

        assertThat(command()).containsEntry("status", "REFUSED_BY_ROVER").containsEntry("failure_code", "SAFE_MODE");
    }

    @Test
    void reportAboutAnUnknownCommandIsKeptButAppliedToNothing() throws Exception {
        send(new TelemetryPayload.CommandStatus(999_999, CommandExecutionStatus.COMPLETED, 100, null, null), MARS_T)
                .andExpect(status().isAccepted());

        assertThat(telemetryCount()).isEqualTo(1);
        assertThat(command()).containsEntry("status", "TRANSMITTED");
    }

    // --- dedupe --------------------------------------------------------------------------------

    @Test
    void repeatedDeliveryTakesEffectOnce() throws Exception {
        TelemetryPacket packet = new TelemetryPacket(UUID.randomUUID(), 1, MARS_T, report(CommandExecutionStatus.EXECUTING, 33));

        send(packet).andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(false));
        send(packet).andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(true));

        assertThat(telemetryCount()).isEqualTo(1);
        assertThat(command()).containsEntry("progress_percent", 33);
    }

    // --- rover state ---------------------------------------------------------------------------

    @Test
    void newerStateBecomesTheLastKnownStateAndChangesTheETag() throws Exception {
        String viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
        String before = mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        send(state(32, 30, 97.5, RoverMode.NOMINAL), MARS_T);

        mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, before))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, not(before)))
                .andExpect(jsonPath("$.lastKnown.y").value(30))
                .andExpect(jsonPath("$.lastKnown.batteryPercent").value(97.5))
                .andExpect(jsonPath("$.lastKnown.reportedAt").value("2026-10-26T12:00:10Z"));
    }

    @Test
    void olderStateDoesNotOverwriteANewerOne() throws Exception {
        send(state(32, 29, 97, RoverMode.SAFE_MODE), MARS_T.plusSeconds(5));
        send(state(32, 31, 99, RoverMode.NOMINAL), MARS_T);

        assertThat(jdbc.sql("SELECT last_y || ' ' || last_mode FROM rovers WHERE id = 1").query(String.class).single())
                .isEqualTo("29 SAFE_MODE");
    }

    // --- rejections ----------------------------------------------------------------------------

    @Test
    void unknownRoverIsPermanentlyUnprocessable() throws Exception {
        send(new TelemetryPacket(UUID.randomUUID(), 42, MARS_T, state(0, 0, 50, RoverMode.NOMINAL)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/unknown-rover"));
    }

    @Test
    void payloadOutsideTheDictionaryIsRejected() throws Exception {
        send(state(32, 32, 150, RoverMode.NOMINAL), MARS_T)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/validation-error"));
        assertThat(telemetryCount()).isZero();
    }

    @Test
    void unsignedTelemetryIsRejected() throws Exception {
        byte[] body = jsonMapper.writeValueAsBytes(new TelemetryPacket(UUID.randomUUID(), 1, MARS_T,
                state(0, 0, 50, RoverMode.NOMINAL)));
        mvc.perform(post("/dsn/downlink").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(telemetryCount()).isZero();
    }

    // --- history -------------------------------------------------------------------------------

    @Test
    void historyIsNewestFirstAndFilterable() throws Exception {
        send(report(CommandExecutionStatus.RECEIVED, 0), MARS_T);
        send(state(32, 31, 99, RoverMode.NOMINAL), MARS_T.plusSeconds(1));
        send(new TelemetryPayload.Event("DUST_STORM_STARTED", EventSeverity.WARNING, "Solar gain reduced"), MARS_T.plusSeconds(2));
        String viewer = TestTokens.bearer(jwtEncoder, "VIEWER");

        mvc.perform(get("/api/v1/rovers/1/telemetry").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].type").value("EVENT"))
                .andExpect(jsonPath("$.content[0].payload.code").value("DUST_STORM_STARTED"))
                .andExpect(jsonPath("$.content[2].commandId").value(commandId));
        mvc.perform(get("/api/v1/rovers/1/telemetry?type=ROVER_STATE").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].payload.y").value(31));
        mvc.perform(get("/api/v1/rovers/1/telemetry")
                        .param("from", "2026-10-26T12:00:11Z").param("to", "2026-10-26T12:00:12Z")
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ROVER_STATE"));
    }

    @Test
    void invertedTimeRangeIs400() throws Exception {
        mvc.perform(get("/api/v1/rovers/1/telemetry")
                        .param("from", "2026-10-26T13:00:00Z").param("to", "2026-10-26T12:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(jwtEncoder, "VIEWER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("from: must be before to"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private TelemetryPayload.CommandStatus report(CommandExecutionStatus status, int progress) {
        return new TelemetryPayload.CommandStatus(commandId, status, progress, null, null);
    }

    private static TelemetryPayload.RoverState state(int x, int y, double battery, RoverMode mode) {
        return new TelemetryPayload.RoverState(x, y, Heading.NORTH, battery, -20, 0, mode);
    }

    private ResultActions send(TelemetryPayload payload, Instant roverTime) throws Exception {
        return send(new TelemetryPacket(UUID.randomUUID(), 1, roverTime, payload));
    }

    /** Signed exactly as rover-sim signs its downlink. */
    private ResultActions send(TelemetryPacket packet) throws Exception {
        byte[] body = jsonMapper.writeValueAsBytes(packet);
        return mvc.perform(post("/dsn/downlink").contentType(MediaType.APPLICATION_JSON)
                .header(PacketSigner.HEADER, signer.sign(body)).content(body));
    }

    private Map<String, Object> command() {
        return jdbc.sql("SELECT status, progress_percent, failure_code, failure_detail FROM commands WHERE id = ?")
                .param(commandId).query().singleRow();
    }

    private long telemetryCount() {
        return jdbc.sql("SELECT count(*) FROM telemetry").query(Long.class).single();
    }
}
