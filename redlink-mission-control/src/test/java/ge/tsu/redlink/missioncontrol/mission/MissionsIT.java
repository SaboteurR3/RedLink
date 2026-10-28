package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.site.SiteService;
import ge.tsu.redlink.missioncontrol.telemetry.TelemetryService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Missions against real PostgreSQL: plan check, idempotent submission, uplink order, abort and cancel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class MissionsIT {

    /** Possible from (32,32) whatever the terrain: nothing moves. */
    private static final String SURVEY = """
            {"name": "Survey", "steps": [
              {"type": "TAKE_PHOTO", "camera": "NAVCAM"},
              {"type": "ROTATE", "heading": "EAST"},
              {"type": "CHARGE", "minutes": 5},
              {"type": "DRILL_SAMPLE"}]}""";

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
    TelemetryService telemetry;
    @Autowired
    SiteService sites;

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
        jdbc.sql("DELETE FROM missions").update();
        jdbc.sql("UPDATE uplink_sequences SET last_sequence_no = 0").update();
        placeRover(32, 32, "NORTH");
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR", "olga");
        director = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR", "dmitri");
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
    }

    // --- submission ----------------------------------------------------------------------------

    @Test
    void feasiblePlanIsQueuedStepByStepWithItsForecast() throws Exception {
        MvcResult result = submit(UUID.randomUUID().toString(), SURVEY)
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.matchesPattern("/api/v1/missions/\\d+")))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.name").value("Survey"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.progress.completed").value(0))
                .andExpect(jsonPath("$.progress.total").value(4))
                // 100 − 0.5 (photo) − 0.2 (turn) → charge caps at 100 → − 8 (drill) = 92; storage 2 + 5
                .andExpect(jsonPath("$.forecast.heading").value("EAST"))
                .andExpect(jsonPath("$.forecast.batteryPercent").value(92.0))
                .andExpect(jsonPath("$.forecast.storageUsedPercent").value(7))
                .andExpect(jsonPath("$.steps.length()").value(4))
                .andExpect(jsonPath("$.steps[0].type").value("TAKE_PHOTO"))
                .andExpect(jsonPath("$.steps[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.steps[0].mission.step").value(1))
                .andExpect(jsonPath("$.steps[3].type").value("DRILL_SAMPLE"))
                .andExpect(jsonPath("$.steps[3].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$._links.cancel.method").value("POST"))
                .andReturn();

        long missionId = idFrom(result);
        assertThat(jdbc.sql("SELECT mission_step FROM commands WHERE mission_id = ? ORDER BY id").param(missionId)
                .query(Integer.class).list()).containsExactly(1, 2, 3, 4);
        long firstStep = jdbc.sql("SELECT id FROM commands WHERE mission_id = ? AND mission_step = 1").param(missionId)
                .query(Long.class).single();
        mvc.perform(get("/api/v1/commands/" + firstStep).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.mission.id").value(missionId))
                .andExpect(jsonPath("$._links.mission.href").value("/api/v1/missions/" + missionId));
    }

    @Test
    void retryWithTheSameKeyReplaysAndAnotherPlanUnderItIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = submit(key, SURVEY).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        submit(key, SURVEY)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().string(first));
        submit(key, SURVEY.replace("Survey", "Other"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/idempotency-key-reused"));
        assertThat(count("missions")).isEqualTo(1);
    }

    @Test
    void infeasiblePlanNamesTheStepAndCreatesNothing() throws Exception {
        int[] cell = cellSouthOfARock();
        placeRover(cell[0], cell[1], "NORTH");

        submit(UUID.randomUUID().toString(), """
                {"name": "Into the rock", "steps": [
                  {"type": "TAKE_PHOTO", "camera": "NAVCAM"},
                  {"type": "MOVE", "distanceMeters": 1}]}""")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/mission-plan-infeasible"))
                .andExpect(jsonPath("$.step").value(2))
                .andExpect(jsonPath("$.errors[0]").value(
                        "steps[1]: meter 1 would hit a rock at (" + cell[0] + "," + (cell[1] - 1) + ")"));

        assertThat(count("missions")).isZero();
        assertThat(count("commands")).isZero();
    }

    @Test
    void roverWithUnfinishedCommandsIsBusy() throws Exception {
        mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"REBOOT\"}"));

        submit(UUID.randomUUID().toString(), SURVEY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rover-busy"));
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        String tooMany = "{\"name\":\"Long\",\"steps\":[" + String.join(",",
                Collections.nCopies(51, "{\"type\":\"REBOOT\"}")) + "]}";

        submit(UUID.randomUUID().toString(), "{\"name\":\"Empty\",\"steps\":[]}").andExpect(status().isBadRequest());
        submit(UUID.randomUUID().toString(), tooMany).andExpect(status().isBadRequest());
        submit(UUID.randomUUID().toString(), "{\"name\":\"Bad\",\"steps\":[{\"type\":\"MOVE\",\"distanceMeters\":99}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("steps[0].distanceMeters")));
        submit(UUID.randomUUID().toString(), "{\"name\":\" \",\"steps\":[{\"type\":\"REBOOT\"}]}").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, operator)
                        .contentType(MediaType.APPLICATION_JSON).content(SURVEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-idempotency-key"));
        mvc.perform(post("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(SURVEY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/rovers/999/missions").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(SURVEY))
                .andExpect(status().isNotFound());
        assertThat(count("missions")).isZero();
    }

    // --- uplink order --------------------------------------------------------------------------

    @Test
    void stepWaitingForApprovalHoldsBackTheRestOfTheMission() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(), """
                {"name": "Drill first", "steps": [{"type": "DRILL_SAMPLE"}, {"type": "TAKE_PHOTO", "camera": "MASTCAM"}]}""")
                .andReturn());
        long drill = step(missionId, 1);

        clock.advance(Duration.ofSeconds(10));
        assertThat(transmitter.transmitDue()).as("the photo waits for the drill").isZero();

        mvc.perform(post("/api/v1/commands/" + drill + "/approve").header(HttpHeaders.AUTHORIZATION, director))
                .andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(5));
        assertThat(transmitter.transmitDue()).isEqualTo(1);
        assertThat(transmitter.transmitDue()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT mission_step FROM commands WHERE mission_id = ? ORDER BY sequence_no")
                .param(missionId).query(Integer.class).list()).containsExactly(1, 2);
        mvc.perform(get("/api/v1/missions/" + missionId).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$._links.cancel").doesNotExist());
    }

    // --- abort ---------------------------------------------------------------------------------

    @Test
    void rejectedStepWithdrawsTheStepsAfterIt() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(), """
                {"name": "Drill first", "steps": [{"type": "DRILL_SAMPLE"}, {"type": "TAKE_PHOTO", "camera": "MASTCAM"}]}""")
                .andReturn());

        mvc.perform(post("/api/v1/commands/" + step(missionId, 1) + "/reject").header(HttpHeaders.AUTHORIZATION, director))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/missions/" + missionId).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.steps[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.steps[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[1].failure.code").value("MISSION_ABORTED"))
                .andExpect(jsonPath("$.steps[1].failure.detail").value("Step 1 of mission " + missionId + " did not complete"));
    }

    @Test
    void failureReportedByTheRoverWithdrawsTheStepsStillOnEarth() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(), SURVEY).andReturn());
        clock.advance(Duration.ofSeconds(5));
        assertThat(transmitter.transmitDue()).as("only step 1 may go first").isEqualTo(1);

        telemetry.receive(new TelemetryPacket(UUID.randomUUID(), 1, T0.plusSeconds(20),
                new TelemetryPayload.CommandStatus(step(missionId, 1), CommandExecutionStatus.FAILED, 0,
                        "STORAGE_FULL", "Storage is 100% full")));

        assertThat(jdbc.sql("SELECT status || ' ' || coalesce(failure_code, '') FROM commands WHERE mission_id = ? ORDER BY mission_step")
                .param(missionId).query(String.class).list())
                .containsExactly("FAILED STORAGE_FULL", "CANCELLED MISSION_ABORTED", "CANCELLED MISSION_ABORTED",
                        "CANCELLED MISSION_ABORTED");
    }

    // --- read and cancel -----------------------------------------------------------------------

    @Test
    void entityTagChangesWhenAStepChanges() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(), SURVEY).andReturn());
        String tag = mvc.perform(get("/api/v1/missions/" + missionId).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/api/v1/missions/" + missionId).header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isNotModified());

        clock.advance(Duration.ofSeconds(5));
        transmitter.transmitDue();

        mvc.perform(get("/api/v1/missions/" + missionId).header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, org.hamcrest.Matchers.not(tag)))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void cancelWithdrawsEverythingStillOnEarthAndCanBeRepeated() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(), SURVEY).andReturn());
        clock.advance(Duration.ofSeconds(5));
        transmitter.transmitDue(); // step 1 leaves Earth

        cancel(missionId)
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[0].status").value("TRANSMITTED"))
                .andExpect(jsonPath("$.steps[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.steps[3].status").value("CANCELLED"))
                .andExpect(jsonPath("$._links.cancel").doesNotExist());
        cancel(missionId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void missionThatHasLeftEarthCannotBeCancelled() throws Exception {
        long missionId = idFrom(submit(UUID.randomUUID().toString(),
                "{\"name\":\"One photo\",\"steps\":[{\"type\":\"TAKE_PHOTO\",\"camera\":\"NAVCAM\"}]}").andReturn());
        clock.advance(Duration.ofSeconds(5));
        transmitter.transmitDue();

        cancel(missionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/mission-not-cancellable"));
    }

    @Test
    void missionsAreListedNewestFirstAndUnknownOnesAre404() throws Exception {
        long first = idFrom(submit(UUID.randomUUID().toString(), SURVEY).andReturn());
        cancel(first);
        long second = idFrom(submit(UUID.randomUUID().toString(), SURVEY.replace("Survey", "Second")).andReturn());

        mvc.perform(get("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first))
                .andExpect(jsonPath("$.content[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.content[1].steps.length()").value(4));
        mvc.perform(get("/api/v1/missions/999999").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/mission-not-found"));
        mvc.perform(get("/api/v1/missions/" + first)).andExpect(status().isUnauthorized());
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions submit(String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/missions").header(HttpHeaders.AUTHORIZATION, operator)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions cancel(long missionId) throws Exception {
        return mvc.perform(post("/api/v1/missions/" + missionId + "/cancel").header(HttpHeaders.AUTHORIZATION, operator));
    }

    private static long idFrom(MvcResult result) {
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).as("Location of a created mission").isNotNull();
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private long step(long missionId, int step) {
        return jdbc.sql("SELECT id FROM commands WHERE mission_id = ? AND mission_step = ?").params(missionId, step)
                .query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void placeRover(int x, int y, String heading) {
        jdbc.sql("""
                UPDATE rovers SET last_x = ?, last_y = ?, last_heading = ?, last_battery_percent = 100,
                       last_storage_used_percent = 0, last_mode = 'NOMINAL' WHERE id = 1""")
                .params(x, y, heading).update();
    }

    /** A drivable cell whose northern neighbour is a rock, on the shipped site. */
    private int[] cellSouthOfARock() {
        Terrain terrain = sites.get("jezero-sim").terrain();
        for (int y = 1; y < terrain.height(); y++) {
            for (int x = 0; x < terrain.width(); x++) {
                if (terrain.surfaceAt(x, y - 1) == SurfaceType.ROCK && !terrain.isBlocked(x, y)) {
                    return new int[]{x, y};
                }
            }
        }
        throw new IllegalStateException("jezero-sim has no rocks");
    }
}
