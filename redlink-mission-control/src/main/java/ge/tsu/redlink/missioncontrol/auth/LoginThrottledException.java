package ge.tsu.redlink.missioncontrol.auth;

import java.time.Duration;

public class LoginThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public LoginThrottledException(Duration retryAfter) {
        super("Too many failed login attempts");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds until the next attempt is allowed, at least 1 (for the Retry-After header). */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
