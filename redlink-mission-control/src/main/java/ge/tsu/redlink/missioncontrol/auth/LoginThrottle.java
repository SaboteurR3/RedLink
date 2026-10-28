package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.security.SecurityProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing: after {@code maxFailures} failed logins for the same
 * (username, client IP) within {@code window}, further attempts are refused until the window ends.
 *
 * <p>Keyed on both, so an attacker cannot lock a real user out from a different machine.
 * In-memory: enough for one instance; several instances would need a shared store (e.g. Redis).
 */
@Component
public class LoginThrottle {

    /** Above this many tracked keys, expired entries are purged so memory stays bounded. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Failures> failures = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    public LoginThrottle(SecurityProperties properties, Clock clock) {
        this.maxFailures = properties.loginThrottle().maxFailures();
        this.window = properties.loginThrottle().window();
        this.clock = clock;
    }

    /**
     * @throws LoginThrottledException if this key has used up its attempts
     */
    public void checkAllowed(String username, String clientIp) {
        Instant now = clock.instant();
        Failures current = failures.get(key(username, clientIp));
        if (current != null && !current.expired(now, window) && current.count() >= maxFailures) {
            Duration retryAfter = Duration.between(now, current.windowStart().plus(window));
            throw new LoginThrottledException(retryAfter);
        }
    }

    public void recordFailure(String username, String clientIp) {
        Instant now = clock.instant();
        failures.compute(key(username, clientIp), (key, current) ->
                current == null || current.expired(now, window)
                        ? new Failures(now, 1)
                        : new Failures(current.windowStart(), current.count() + 1));
        if (failures.size() > PURGE_THRESHOLD) {
            failures.values().removeIf(entry -> entry.expired(now, window));
        }
    }

    public void recordSuccess(String username, String clientIp) {
        failures.remove(key(username, clientIp));
    }

    private static String key(String username, String clientIp) {
        return username + '|' + clientIp;
    }

    private record Failures(Instant windowStart, int count) {

        boolean expired(Instant now, Duration window) {
            return !now.isBefore(windowStart.plus(window));
        }
    }
}
