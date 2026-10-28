package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.security.SecurityProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Slows down password guessing: after {@code maxFailures} failed logins for the same
 * (username, client IP) within {@code window}, further attempts are refused until the window ends.
 *
 * <p>Keyed on both, so an attacker cannot lock a real user out from a different machine. Kept in
 * PostgreSQL (ticket 34), so the limit holds across every instance: guessing against several
 * instances behind a load balancer does not multiply the attempts.
 */
@Component
public class LoginThrottle {

    private final JdbcClient jdbc;
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    public LoginThrottle(JdbcClient jdbc, SecurityProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.maxFailures = properties.loginThrottle().maxFailures();
        this.window = properties.loginThrottle().window();
        this.clock = clock;
    }

    /**
     * @throws LoginThrottledException if this key has used up its attempts
     */
    public void checkAllowed(String username, String clientIp) {
        Instant now = now();
        Optional<Instant> windowStart = jdbc.sql("""
                        SELECT window_start FROM login_failures
                        WHERE username = ? AND client_ip = ? AND window_start > ? AND failures >= ?""")
                .params(username, clientIp, Timestamp.from(now.minus(window)), maxFailures)
                .query((rs, row) -> rs.getTimestamp("window_start").toInstant())
                .optional();
        if (windowStart.isPresent()) {
            throw new LoginThrottledException(Duration.between(now, windowStart.get().plus(window)));
        }
    }

    /** One atomic upsert: concurrent failures on several instances are all counted. */
    public void recordFailure(String username, String clientIp) {
        Instant now = now();
        Timestamp expiredBefore = Timestamp.from(now.minus(window));
        jdbc.sql("""
                        INSERT INTO login_failures (username, client_ip, window_start, failures)
                        VALUES (:username, :clientIp, :now, 1)
                        ON CONFLICT (username, client_ip) DO UPDATE SET
                            failures = CASE WHEN login_failures.window_start <= :expiredBefore THEN 1
                                            ELSE login_failures.failures + 1 END,
                            window_start = CASE WHEN login_failures.window_start <= :expiredBefore THEN :now
                                                ELSE login_failures.window_start END""")
                .param("username", username)
                .param("clientIp", clientIp)
                .param("now", Timestamp.from(now))
                .param("expiredBefore", expiredBefore)
                .update();
        // Old windows are useless; dropping them keeps the table as small as the attack.
        jdbc.sql("DELETE FROM login_failures WHERE window_start <= ?").param(expiredBefore).update();
    }

    public void recordSuccess(String username, String clientIp) {
        jdbc.sql("DELETE FROM login_failures WHERE username = ? AND client_ip = ?").params(username, clientIp).update();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
