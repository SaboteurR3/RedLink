package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.MutableClock;
import ge.tsu.redlink.missioncontrol.MutableClockConfiguration;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import ge.tsu.redlink.missioncontrol.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static ge.tsu.redlink.missioncontrol.MutableClockConfiguration.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The login throttle in PostgreSQL (ticket 34): 5 failures per (user, client IP) in 15 minutes,
 * shared by every instance.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, MutableClockConfiguration.class})
@ActiveProfiles("test")
class LoginThrottleIT {

    @Autowired
    LoginThrottle throttle;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    SecurityProperties properties;

    @BeforeEach
    void reset() {
        clock.set(T0);
        jdbc.sql("DELETE FROM login_failures").update();
    }

    @Test
    void sixthAttemptWithinTheWindowIsRefusedUntilTheWindowEnds() {
        failTimes(throttle, 5);
        clock.advance(Duration.ofMinutes(5));

        LoginThrottledException refused = catchThrowableOfType(LoginThrottledException.class,
                () -> throttle.checkAllowed("viewer", "10.0.0.66"));
        assertThat(refused.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());

        clock.advance(Duration.ofMinutes(10));
        assertThatCode(() -> throttle.checkAllowed("viewer", "10.0.0.66")).doesNotThrowAnyException();
    }

    @Test
    void anotherClientOrUserIsNotAffectedAndSuccessClearsTheCount() {
        failTimes(throttle, 5);

        assertThatCode(() -> throttle.checkAllowed("viewer", "10.0.0.67")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.checkAllowed("operator", "10.0.0.66")).doesNotThrowAnyException();

        throttle.recordSuccess("viewer", "10.0.0.66");
        assertThatCode(() -> throttle.checkAllowed("viewer", "10.0.0.66")).doesNotThrowAnyException();
    }

    @Test
    void instancesShareTheCountSoMoreInstancesDoNotMeanMoreGuesses() {
        // A second mission-control instance: its own object, the same database.
        LoginThrottle otherInstance = new LoginThrottle(jdbc, properties, clock);
        failTimes(throttle, 3);
        failTimes(otherInstance, 2);

        assertThat(catchThrowableOfType(LoginThrottledException.class, () -> throttle.checkAllowed("viewer", "10.0.0.66")))
                .isNotNull();
        assertThat(catchThrowableOfType(LoginThrottledException.class, () -> otherInstance.checkAllowed("viewer", "10.0.0.66")))
                .isNotNull();
    }

    @Test
    void anExpiredWindowStartsAgainAndOldRowsAreDropped() {
        failTimes(throttle, 4);
        clock.advance(Duration.ofMinutes(16));
        throttle.recordFailure("viewer", "10.0.0.66");

        assertThat(jdbc.sql("SELECT failures FROM login_failures WHERE username = 'viewer'").query(Integer.class).single())
                .isEqualTo(1);
        throttle.recordFailure("operator", "10.0.0.1");
        clock.advance(Duration.ofMinutes(16));
        throttle.recordFailure("director", "10.0.0.2");
        assertThat(jdbc.sql("SELECT count(*) FROM login_failures").query(Long.class).single()).isEqualTo(1);
    }

    private static void failTimes(LoginThrottle throttle, int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure("viewer", "10.0.0.66");
        }
    }
}
