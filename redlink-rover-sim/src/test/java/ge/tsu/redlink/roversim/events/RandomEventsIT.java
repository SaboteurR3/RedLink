package ge.tsu.redlink.roversim.events;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.rover.MarsClock;
import ge.tsu.redlink.roversim.MutableClock;
import ge.tsu.redlink.roversim.MutableClockConfiguration;
import ge.tsu.redlink.roversim.TestcontainersConfiguration;
import ge.tsu.redlink.roversim.flight.CommandStepper;
import ge.tsu.redlink.roversim.uplink.UplinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Dust storms, stuck wheels and solar charging through the sol, against real PostgreSQL, with scripted dice: a roll below the chance
 * makes the event happen, an empty script means "nothing happens".
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class, RandomEventsIT.ScriptedDiceConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "redlink.rover.events.enabled=true",
        "redlink.rover.events.weather-interval=1m",
        "redlink.rover.events.dust-storm-chance-per-minute=0.5",
        "redlink.rover.events.dust-storm-duration=2m",
        "redlink.rover.events.wheel-stuck-chance-per-meter=0.5"})
class RandomEventsIT {

    /** T0 is midnight on the Mars clock; these are the same sol's noon and sunset. */
    private static final Instant T0 = MutableClockConfiguration.T0;
    private static final Instant NOON = T0.plus(MarsClock.SOL.dividedBy(2));
    private static final Instant SUNSET = T0.plus(MarsClock.SOL.multipliedBy(3).dividedBy(4));

    @Autowired
    Weather weather;
    @Autowired
    CommandStepper stepper;
    @Autowired
    UplinkService uplink;
    @Autowired
    MutableClock clock;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ScriptedDice dice;

    private long nextCommandId = 5000;

    @BeforeEach
    void reset() {
        clock.set(T0);
        dice.rolls.clear();
        jdbc.sql("DELETE FROM downlink_packets").update();
        jdbc.sql("DELETE FROM received_commands").update();
        jdbc.sql("""
                UPDATE rover_state SET x = 32, y = 32, heading = 'NORTH', battery_percent = 50, storage_used_percent = 0,
                       mode = 'NOMINAL', last_sequence_no = 0, dust_storm_until = NULL WHERE rover_id = 1""").update();
    }

    @Test
    void dustStormComesAndGoesAndEarthHearsAboutBoth() {
        dice.rolls.add(0.9);
        weather.update(1);
        assertThat(stormUntil()).as("0.9 is above the chance: clear sky").isNull();

        dice.rolls.add(0.1);
        weather.update(1);
        assertThat(stormUntil()).isEqualTo(T0.plus(Duration.ofMinutes(2)));

        clock.advance(Duration.ofMinutes(1));
        weather.update(1);
        assertThat(stormUntil()).as("still blowing").isNotNull();

        clock.advance(Duration.ofMinutes(1));
        weather.update(1);
        assertThat(stormUntil()).isNull();
        assertThat(events()).containsExactly("DUST_STORM_STARTED WARNING", "DUST_STORM_ENDED INFO");
    }

    @Test
    void chargingAtNoonGainsTheFullRate() {
        clock.set(NOON);
        long id = receive(new RoverCommand.Charge(4));

        tick(Duration.ZERO);
        for (int minute = 0; minute < 4; minute++) {
            tick(Duration.ofSeconds(1)); // test charge-minute is 1 s
        }

        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(battery()).isCloseTo(54.0, within(0.01));
        assertThat(events()).isEmpty();
    }

    @Test
    void chargingAtNightGainsNothingAndSaysSoOnce() {
        clock.set(T0); // midnight on the Mars clock
        long id = receive(new RoverCommand.Charge(3));

        tick(Duration.ZERO);
        for (int minute = 0; minute < 3; minute++) {
            tick(Duration.ofSeconds(1));
        }

        assertThat(status(id)).as("still runs: night is no reason to refuse").isEqualTo("COMPLETED");
        assertThat(battery()).isEqualTo(50.0);
        assertThat(events()).containsExactly("NO_SUNLIGHT WARNING");
        assertThat(jdbc.sql("SELECT packet->'payload'->>'message' FROM downlink_packets WHERE type = 'EVENT'")
                .query(String.class).single()).contains("until sunrise in about 179 s"); // first dark minute is 1 s after midnight
    }

    @Test
    void chargingIntoTheSunsetWarnsAsTheDarkBegins() {
        clock.set(SUNSET.minusMillis(1500));
        receive(new RoverCommand.Charge(3));

        tick(Duration.ZERO);
        tick(Duration.ofSeconds(1)); // the sun is just above the horizon
        assertThat(events()).isEmpty();
        tick(Duration.ofSeconds(1)); // dark
        tick(Duration.ofSeconds(1)); // still dark: no second warning

        assertThat(events()).containsExactly("NO_SUNLIGHT WARNING");
    }

    @Test
    void chargingDuringADustStormGainsAQuarter() {
        clock.set(NOON);
        jdbc.sql("UPDATE rover_state SET dust_storm_until = ? WHERE rover_id = 1")
                .param(java.sql.Timestamp.from(T0.plus(Duration.ofHours(1)))).update();
        long id = receive(new RoverCommand.Charge(4));

        tick(Duration.ZERO);
        for (int minute = 0; minute < 4; minute++) {
            tick(Duration.ofSeconds(1)); // test charge-minute is 1 s
        }

        assertThat(status(id)).isEqualTo("COMPLETED");
        assertThat(battery()).isCloseTo(51.0, within(0.01)); // 4 × 0.25 instead of 4 × 1, at noon
    }

    @Test
    void stuckWheelStopsTheDriveButNotTheRover() {
        long id = receive(new RoverCommand.Move(3));
        dice.rolls.addAll(List.of(0.9, 0.1)); // first meter fine, second stuck

        tick(Duration.ZERO);
        tick(Duration.ofSeconds(1));
        tick(Duration.ofSeconds(1));

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT failure_code FROM received_commands WHERE command_id = ?").param(id)
                .query(String.class).single()).isEqualTo("WHEEL_STUCK");
        assertThat(jdbc.sql("SELECT x || ',' || y || ' ' || mode FROM rover_state WHERE rover_id = 1")
                .query(String.class).single()).as("one meter driven, no safe mode").isEqualTo("32,31 NOMINAL");
    }

    private long receive(RoverCommand command) {
        long commandId = nextCommandId++;
        uplink.receive(new CommandPacket(UUID.randomUUID(), 1, commandId, 1, command, clock.instant()));
        return commandId;
    }

    private void tick(Duration elapsed) {
        clock.advance(elapsed);
        stepper.advance(1);
    }

    private Instant stormUntil() {
        return jdbc.sql("SELECT dust_storm_until FROM rover_state WHERE rover_id = 1")
                .query(java.time.OffsetDateTime.class).optional().map(java.time.OffsetDateTime::toInstant).orElse(null);
    }

    private double battery() {
        return jdbc.sql("SELECT battery_percent FROM rover_state WHERE rover_id = 1").query(Double.class).single();
    }

    private String status(long commandId) {
        return jdbc.sql("SELECT status FROM received_commands WHERE command_id = ?").param(commandId)
                .query(String.class).single();
    }

    private List<String> events() {
        return jdbc.sql("""
                        SELECT packet->'payload'->>'code' || ' ' || (packet->'payload'->>'severity')
                        FROM downlink_packets WHERE type = 'EVENT' ORDER BY created_at""")
                .query(String.class).list();
    }

    /** Rolls what the test queued; when the queue is empty, rolls 0.999 (nothing happens). */
    static class ScriptedDice implements Dice {

        final ConcurrentLinkedQueue<Double> rolls = new ConcurrentLinkedQueue<>();

        @Override
        public double roll() {
            Double next = rolls.poll();
            return next == null ? 0.999 : next;
        }
    }

    @TestConfiguration
    static class ScriptedDiceConfig {

        @Bean
        @Primary
        ScriptedDice scriptedDice() {
            return new ScriptedDice();
        }
    }
}
