package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverMode;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The SSE stream against real PostgreSQL: tickets, snapshot, live events after commit, resume.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class EventsIT {

    private static final String EVENTS = "/api/v1/rovers/1/events";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    TelemetryService telemetry;
    @Autowired
    UplinkTransmitter transmitter;
    @Autowired
    JsonMapper jsonMapper;

    private String viewer;
    private String operator;

    @BeforeEach
    void reset() {
        clock.set(T0);
        jdbc.sql("DELETE FROM rover_events").update();
        jdbc.sql("DELETE FROM telemetry").update();
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("""
                UPDATE rovers SET last_x = 32, last_y = 32, last_battery_percent = 100, last_mode = 'NOMINAL',
                       last_telemetry_at = NULL WHERE id = 1""").update();
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR");
    }

    // --- tickets -------------------------------------------------------------------------------

    @Test
    void ticketIsIssuedAsAnUncacheableCredential() throws Exception {
        mvc.perform(post(EVENTS + "/tickets").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.streamUrl").value(startsWith(EVENTS + "?ticket=")));
    }

    @Test
    void ticketOpensTheStreamExactlyOnce() throws Exception {
        String ticket = ticket();

        mvc.perform(get(EVENTS).param("ticket", ticket)).andExpect(request().asyncStarted());

        mvc.perform(get(EVENTS).param("ticket", ticket))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-stream-ticket"));
    }

    @Test
    void ticketExpires() throws Exception {
        String ticket = ticket();
        clock.advance(Duration.ofSeconds(30));

        mvc.perform(get(EVENTS).param("ticket", ticket)).andExpect(status().isUnauthorized());
    }

    @Test
    void ticketIsBoundToItsRover() throws Exception {
        mvc.perform(get("/api/v1/rovers/2/events").param("ticket", ticket())).andExpect(status().isUnauthorized());
    }

    @Test
    void streamNeedsAuthenticationButAcceptsBearerTokensToo() throws Exception {
        mvc.perform(get(EVENTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(EVENTS).param("ticket", "made-up")).andExpect(status().isUnauthorized());
        mvc.perform(get(EVENTS).header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(request().asyncStarted());
    }

    // --- content -------------------------------------------------------------------------------

    @Test
    void streamStartsWithASnapshotOfTheRover() throws Exception {
        MockHttpServletResponse stream = open();

        List<Sse> events = awaitEvents(stream, 1);
        assertThat(events.get(0).name()).isEqualTo("rover");
        assertThat(jsonMapper.readTree(events.get(0).data()).get("lastKnown").get("x").asInt()).isEqualTo(32);
        assertThat(stream.getContentAsString()).contains("retry:3000");
        assertThat(stream.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(stream.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void commandLifecycleIsStreamedAsItHappens() throws Exception {
        MockHttpServletResponse stream = open();
        awaitEvents(stream, 1);

        submit("{\"type\":\"MOVE\",\"distanceMeters\":2}");
        List<Sse> afterSubmit = awaitEvents(stream, 2);
        assertThat(afterSubmit.get(1).name()).isEqualTo("command");
        assertThat(afterSubmit.get(1).data()).contains("\"status\":\"QUEUED\"");

        clock.advance(Duration.ofSeconds(5));
        transmitter.transmitDue();
        List<Sse> afterTransmit = awaitEvents(stream, 4);
        assertThat(afterTransmit.get(2).name()).isEqualTo("command");
        assertThat(afterTransmit.get(2).data()).contains("\"status\":\"TRANSMITTED\"");
        assertThat(afterTransmit.get(3).name()).isEqualTo("uplink");
        assertThat(afterTransmit.get(3).data()).contains("\"arrivesAt\":\"2026-10-26T12:00:15Z\"");

        telemetry.receive(new TelemetryPacket(UUID.randomUUID(), 1, T0.plusSeconds(16),
                new TelemetryPayload.RoverState(32, 31, Heading.NORTH, 99, -20, 0, RoverMode.NOMINAL)));
        List<Sse> afterTelemetry = awaitEvents(stream, 5);
        assertThat(afterTelemetry.get(4).name()).isEqualTo("rover");
        assertThat(afterTelemetry.get(4).data()).contains("\"y\":31");

        // ids grow with every event, so a client can resume from any of them
        assertThat(afterTelemetry.stream().skip(1).map(Sse::id).toList()).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void aRequestThatFailsIsNeverStreamed() throws Exception {
        MockHttpServletResponse stream = open();
        awaitEvents(stream, 1);
        jdbc.sql("UPDATE rovers SET last_battery_percent = 3 WHERE id = 1").update();

        mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"MOVE\",\"distanceMeters\":5}"))
                .andExpect(status().isUnprocessableContent());
        submit("{\"type\":\"TAKE_PHOTO\",\"camera\":\"NAVCAM\"}");

        List<Sse> events = awaitEvents(stream, 2);
        assertThat(events.get(1).data()).contains("TAKE_PHOTO");
        assertThat(stream.getContentAsString()).doesNotContain("\"MOVE\"");
    }

    // --- resume --------------------------------------------------------------------------------

    @Test
    void reconnectingClientGetsExactlyWhatItMissed() throws Exception {
        MockHttpServletResponse first = open();
        long seen = Long.parseLong(awaitEvents(first, 1).get(0).id());
        submit("{\"type\":\"TAKE_PHOTO\",\"camera\":\"NAVCAM\"}");
        long lastSeen = Long.parseLong(awaitEvents(first, 2).get(1).id());
        // the client disconnects; meanwhile two more commands happen
        submit("{\"type\":\"ROTATE\",\"heading\":\"EAST\"}");
        submit("{\"type\":\"REBOOT\"}");

        MvcResult resumed = mvc.perform(get(EVENTS).header(HttpHeaders.AUTHORIZATION, viewer)
                .header("Last-Event-ID", String.valueOf(lastSeen))).andExpect(request().asyncStarted()).andReturn();

        List<Sse> replayed = awaitEvents(resumed.getResponse(), 2);
        assertThat(replayed).extracting(Sse::name).containsExactly("command", "command");
        assertThat(replayed.get(0).data()).contains("ROTATE");
        assertThat(replayed.get(1).data()).contains("REBOOT");
        assertThat(seen).isLessThan(lastSeen);
    }

    @Test
    void newEventSourceCanResumeWithAQueryParameter() throws Exception {
        MockHttpServletResponse first = open();
        long seen = Long.parseLong(awaitEvents(first, 1).get(0).id());
        submit("{\"type\":\"REBOOT\"}");

        MvcResult resumed = mvc.perform(get(EVENTS).param("ticket", ticket()).param("lastEventId", String.valueOf(seen)))
                .andExpect(request().asyncStarted()).andReturn();

        assertThat(awaitEvents(resumed.getResponse(), 1).get(0).name()).isEqualTo("command");
    }

    @Test
    void clientTooFarBehindIsToldToStartOver() throws Exception {
        long before = Long.parseLong(awaitEvents(open(), 1).get(0).id());
        for (int i = 0; i < 4; i++) { // replay-limit is 3 in tests
            submit("{\"type\":\"REBOOT\"}");
        }

        MvcResult resumed = mvc.perform(get(EVENTS).header(HttpHeaders.AUTHORIZATION, viewer)
                .header("Last-Event-ID", String.valueOf(before))).andExpect(request().asyncStarted()).andReturn();

        List<Sse> events = awaitEvents(resumed.getResponse(), 1);
        assertThat(events.get(0).name()).isEqualTo("reset");
        assertThat(events.get(0).data()).contains("\"lastKnown\"");
    }

    // --- helpers -------------------------------------------------------------------------------

    private String ticket() throws Exception {
        String body = mvc.perform(post(EVENTS + "/tickets").header(HttpHeaders.AUTHORIZATION, viewer))
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).get("ticket").asString();
    }

    private MockHttpServletResponse open() throws Exception {
        return mvc.perform(get(EVENTS).param("ticket", ticket()))
                .andExpect(request().asyncStarted()).andReturn().getResponse();
    }

    private void submit(String command) throws Exception {
        mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(command))
                .andExpect(status().isAccepted());
    }

    /** Waits until the stream holds at least {@code count} events and returns them, oldest first. */
    private static List<Sse> awaitEvents(MockHttpServletResponse stream, int count) {
        return await().atMost(Duration.ofSeconds(5)).until(() -> parse(stream.getContentAsString()), events -> events.size() >= count);
    }

    /** Minimal text/event-stream parser: blank line ends an event; comments (":") are skipped. */
    private static List<Sse> parse(String content) {
        List<Sse> events = new ArrayList<>();
        for (String block : content.split("\n\n")) {
            String id = null;
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6);
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5));
                }
            }
            if (name != null) {
                events.add(new Sse(id, name, data.toString()));
            }
        }
        return events;
    }

    private record Sse(String id, String name, String data) {
    }
}
