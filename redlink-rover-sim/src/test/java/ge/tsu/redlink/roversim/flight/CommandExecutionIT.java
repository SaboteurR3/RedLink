package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.link.TraceContext;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.contract.terrain.SurfaceType;
import ge.tsu.redlink.contract.terrain.Terrain;
import ge.tsu.redlink.roversim.MutableClock;
import ge.tsu.redlink.roversim.MutableClockConfiguration;
import ge.tsu.redlink.roversim.TestcontainersConfiguration;
import ge.tsu.redlink.roversim.camera.PhotoRenderer;
import ge.tsu.redlink.roversim.uplink.UplinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor against real PostgreSQL, driven tick by tick with a hand-moved clock.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class CommandExecutionIT {

    private static final long ROVER = 1;
    private static final Instant T0 = MutableClockConfiguration.T0;

    @Autowired
    CommandStepper stepper;
    @Autowired
    UplinkService uplink;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TerrainRegistry terrains;

    private long nextCommandId = 1000;

    @BeforeEach
    void resetRover() {
        clock.set(T0);
        jdbc.sql("DELETE FROM downlink_packets").update();
        jdbc.sql("DELETE FROM received_commands").update();
        placeRover(32, 32, Heading.NORTH);
        jdbc.sql("""
                UPDATE rover_state SET battery_percent = 100, storage_used_percent = 0, mode = 'NOMINAL',
                       last_sequence_no = 0 WHERE rover_id = 1""").update();
    }

    @Test
    void moveTwoMetersTakesTwoSecondsAndReportsEveryStep() {
        long id = receive(1, new RoverCommand.Move(2));

        tick(Duration.ZERO);
        assertThat(commandStatus(id)).isEqualTo("EXECUTING");
        assertThat(position()).isEqualTo("32,32");

        tick(Duration.ofMillis(999));
        assertThat(position()).as("not due yet").isEqualTo("32,32");

        tick(Duration.ofMillis(1));
        assertThat(position()).isEqualTo("32,31");

        tick(Duration.ofSeconds(1));
        assertThat(position()).isEqualTo("32,30");
        assertThat(commandStatus(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT battery_percent FROM rover_state WHERE rover_id = 1").query(Double.class).single())
                .isEqualTo(98.0);

        assertThat(reportedStatuses(id)).containsExactly("RECEIVED 0", "EXECUTING 0", "EXECUTING 50", "COMPLETED 100");
        assertThat(telemetryCount("ROVER_STATE")).isEqualTo(2);
    }

    @Test
    void commandWithNothingToDoCompletesAtOnce() {
        long id = receive(1, new RoverCommand.Rotate(Heading.NORTH));

        tick(Duration.ZERO);

        assertThat(reportedStatuses(id)).containsExactly("RECEIVED 0", "EXECUTING 0", "COMPLETED 100");
    }

    @Test
    void takePhotoDownlinksThePictureBeforeReportingCompletion() {
        placeRover(20, 40, Heading.EAST);
        long id = receive(1, new RoverCommand.TakePhoto(Camera.MASTCAM));

        tick(Duration.ZERO);
        tick(Duration.ofSeconds(3));

        assertThat(commandStatus(id)).isEqualTo("COMPLETED");
        Map<String, Object> photo = jdbc.sql("""
                        SELECT packet->'payload'->>'commandId' AS command_id, packet->'payload'->>'camera' AS camera,
                               packet->'payload'->>'x' AS x, packet->'payload'->>'heading' AS heading,
                               packet->'payload'->>'imageBase64' AS image, created_at
                        FROM downlink_packets WHERE type = 'PHOTO'""")
                .query().singleRow();
        assertThat(photo).containsEntry("command_id", String.valueOf(id)).containsEntry("camera", "MASTCAM")
                .containsEntry("x", "20").containsEntry("heading", "EAST");
        assertThat(Base64.getDecoder().decode((String) photo.get("image")))
                .isEqualTo(PhotoRenderer.render(terrains.get("jezero-sim"), 20, 40, Heading.EAST, Camera.MASTCAM));
        assertThat(jdbc.sql("SELECT storage_used_percent FROM rover_state WHERE rover_id = 1").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(reportedStatuses(id)).endsWith("COMPLETED 100");
    }

    @Test
    void drillingFacingARockSendsARockSampleAndTheRockStays() {
        int[] cell = cellSouthOfARock();
        placeRover(cell[0], cell[1], Heading.NORTH);
        long id = receive(1, new RoverCommand.DrillSample());

        tick(Duration.ZERO);
        tick(RoverSpecs.DRILL_TIME);

        assertThat(commandStatus(id)).isEqualTo("COMPLETED");
        Map<String, Object> sample = jdbc.sql("""
                        SELECT packet->'payload'->>'commandId' AS command_id, packet->'payload'->>'material' AS material,
                               packet->'payload'->>'x' AS x, packet->'payload'->>'y' AS y,
                               packet->'payload'->>'heading' AS heading
                        FROM downlink_packets WHERE type = 'SAMPLE'""")
                .query().singleRow();
        assertThat(sample).containsEntry("command_id", String.valueOf(id)).containsEntry("material", "ROCK")
                .containsEntry("x", String.valueOf(cell[0])).containsEntry("y", String.valueOf(cell[1]))
                .containsEntry("heading", "NORTH");
        assertThat(terrains.get("jezero-sim").isBlocked(cell[0], cell[1] - 1)).isTrue();
        assertThat(reportedStatuses(id)).endsWith("COMPLETED 100");
    }

    @Test
    void drillingOnOpenGroundSendsASoilSample() {
        int[] cell = cellSouthOfARock();
        placeRover(cell[0], cell[1], Heading.SOUTH); // its back to the rock
        assertThat(terrains.get("jezero-sim").isBlocked(cell[0], cell[1] + 1)).as("open ground ahead").isFalse();
        long id = receive(1, new RoverCommand.DrillSample());

        tick(Duration.ZERO);
        tick(RoverSpecs.DRILL_TIME);

        assertThat(jdbc.sql("SELECT packet->'payload'->>'material' FROM downlink_packets WHERE type = 'SAMPLE'")
                .query(String.class).single()).isEqualTo("SOIL");
    }

    @Test
    void packetsArrivingOutOfOrderRunInSequenceOrder() {
        long second = receive(2, new RoverCommand.Rotate(Heading.WEST));
        tick(Duration.ZERO);
        assertThat(commandStatus(second)).as("waits for sequence 1").isEqualTo("RECEIVED");

        long first = receive(1, new RoverCommand.Rotate(Heading.EAST));
        tick(Duration.ofSeconds(1));
        assertThat(commandStatus(first)).isEqualTo("EXECUTING");

        tick(Duration.ofSeconds(2));       // NORTH → EAST done
        tick(Duration.ZERO);               // start sequence 2: EAST → WEST is two quarter turns
        tick(Duration.ofSeconds(2));
        tick(Duration.ofSeconds(2));

        assertThat(commandStatus(first)).isEqualTo("COMPLETED");
        assertThat(commandStatus(second)).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT heading FROM rover_state WHERE rover_id = 1").query(String.class).single())
                .isEqualTo("WEST");
        assertThat(startedAt(first)).isBefore(startedAt(second));
    }

    @Test
    void missingSequenceIsSkippedAfterTheReorderTimeoutAndExpiresIfItArrivesLater() {
        long second = receive(2, new RoverCommand.Rotate(Heading.EAST));

        tick(Duration.ofSeconds(29));
        assertThat(commandStatus(second)).isEqualTo("RECEIVED");
        assertThat(telemetryCount("EVENT")).isZero();

        tick(Duration.ofSeconds(2));
        assertThat(commandStatus(second)).isEqualTo("EXECUTING");
        assertThat(events()).containsExactly("SEQUENCE_GAP_SKIPPED");

        tick(Duration.ofSeconds(2));       // sequence 2 done
        long late = receive(1, new RoverCommand.Move(1));
        tick(Duration.ZERO);
        assertThat(commandStatus(late)).isEqualTo("REFUSED");
        assertThat(failureCode(late)).isEqualTo("SEQUENCE_EXPIRED");
        assertThat(position()).isEqualTo("32,32");
    }

    @Test
    void drivingIntoARockEntersSafeModeUntilReboot() {
        int[] cell = cellSouthOfARock();
        placeRover(cell[0], cell[1], Heading.NORTH);

        long move = receive(1, new RoverCommand.Move(3));
        tick(Duration.ZERO);
        tick(Duration.ofSeconds(1));

        assertThat(commandStatus(move)).isEqualTo("FAILED");
        assertThat(failureCode(move)).isEqualTo("OBSTACLE");
        assertThat(mode()).isEqualTo("SAFE_MODE");
        assertThat(position()).isEqualTo(cell[0] + "," + cell[1]);
        assertThat(events()).containsExactly("SAFE_MODE_ENTERED");

        long refused = receive(2, new RoverCommand.Rotate(Heading.EAST));
        tick(Duration.ZERO);
        assertThat(commandStatus(refused)).isEqualTo("REFUSED");
        assertThat(failureCode(refused)).isEqualTo("SAFE_MODE");

        long reboot = receive(3, new RoverCommand.Reboot());
        tick(Duration.ZERO);
        tick(Duration.ofSeconds(5));
        assertThat(commandStatus(reboot)).isEqualTo("COMPLETED");
        assertThat(mode()).isEqualTo("NOMINAL");
    }

    @Test
    void everyReportAboutATracedCommandContinuesEarthsTrace() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String earth = "00-" + traceId + "-00f067aa0ba902b7-01";
        long id = nextCommandId++;
        uplink.receive(new CommandPacket(UUID.randomUUID(), ROVER, id, 1, new RoverCommand.Move(1), clock.instant(), earth));
        tick(Duration.ZERO);
        tick(Duration.ofSeconds(1));
        assertThat(commandStatus(id)).isEqualTo("COMPLETED");

        assertThat(jdbc.sql("SELECT trace_parent FROM received_commands WHERE command_id = ?").param(id)
                .query(String.class).single()).isEqualTo(earth);
        // RECEIVED, EXECUTING, the new position and COMPLETED: all in Earth's trace, each from its own span.
        List<String> traces = jdbc.sql("SELECT packet ->> 'traceparent' FROM downlink_packets ORDER BY created_at")
                .query(String.class).list();
        assertThat(traces).hasSizeGreaterThanOrEqualTo(4)
                .allSatisfy(t -> assertThat(TraceContext.traceId(t)).isEqualTo(traceId))
                .doesNotContain(earth);
    }

    // --- helpers -------------------------------------------------------------------------------

    private long receive(long sequenceNo, RoverCommand command) {
        long commandId = nextCommandId++;
        uplink.receive(new CommandPacket(UUID.randomUUID(), ROVER, commandId, sequenceNo, command, clock.instant()));
        return commandId;
    }

    private void tick(Duration elapsed) {
        clock.advance(elapsed);
        stepper.advance(ROVER);
    }

    private void placeRover(int x, int y, Heading heading) {
        jdbc.sql("UPDATE rover_state SET x = ?, y = ?, heading = ? WHERE rover_id = 1")
                .params(x, y, heading.name()).update();
    }

    /** A drivable cell whose northern neighbour is a rock, on the shipped site. */
    private int[] cellSouthOfARock() {
        Terrain terrain = terrains.get("jezero-sim");
        for (int y = 1; y < terrain.height(); y++) {
            for (int x = 0; x < terrain.width(); x++) {
                if (terrain.surfaceAt(x, y - 1) == SurfaceType.ROCK && !terrain.isBlocked(x, y)) {
                    return new int[]{x, y};
                }
            }
        }
        throw new IllegalStateException("jezero-sim has no rocks");
    }

    private String position() {
        return jdbc.sql("SELECT x || ',' || y FROM rover_state WHERE rover_id = 1").query(String.class).single();
    }

    private String mode() {
        return jdbc.sql("SELECT mode FROM rover_state WHERE rover_id = 1").query(String.class).single();
    }

    private String commandStatus(long commandId) {
        return jdbc.sql("SELECT status FROM received_commands WHERE command_id = ?").param(commandId)
                .query(String.class).single();
    }

    private String failureCode(long commandId) {
        return jdbc.sql("SELECT failure_code FROM received_commands WHERE command_id = ?").param(commandId)
                .query(String.class).single();
    }

    private Instant startedAt(long commandId) {
        return jdbc.sql("SELECT started_at FROM received_commands WHERE command_id = ?").param(commandId)
                .query(java.time.OffsetDateTime.class).single().toInstant();
    }

    /** COMMAND_STATUS telemetry for one command, oldest first, as "STATUS progress". */
    private List<String> reportedStatuses(long commandId) {
        return jdbc.sql("""
                        SELECT packet->'payload'->>'status' || ' ' || (packet->'payload'->>'progressPercent')
                        FROM downlink_packets
                        WHERE type = 'COMMAND_STATUS' AND (packet->'payload'->>'commandId')::bigint = ?
                        ORDER BY created_at, (packet->'payload'->>'progressPercent')::int""")
                .param(commandId).query(String.class).list();
    }

    private List<String> events() {
        return jdbc.sql("SELECT packet->'payload'->>'code' FROM downlink_packets WHERE type = 'EVENT' ORDER BY created_at")
                .query(String.class).list();
    }

    private long telemetryCount(String type) {
        return jdbc.sql("SELECT count(*) FROM downlink_packets WHERE type = ?").param(type).query(Long.class).single();
    }
}
