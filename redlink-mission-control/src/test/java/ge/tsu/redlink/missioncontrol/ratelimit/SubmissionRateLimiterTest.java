package ge.tsu.redlink.missioncontrol.ratelimit;

import ge.tsu.redlink.missioncontrol.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class SubmissionRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-28T12:00:00Z"));
    private final SubmissionRateLimiter limiter = new SubmissionRateLimiter(
            new RateLimitProperties(new RateLimitProperties.Limit(3, Duration.ofMinutes(1))), clock);

    @Test
    void burstUpToCapacityThenRefusedWithTheWait() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("alice");
        }
        RateLimitedException refused = catchThrowableOfType(RateLimitedException.class, () -> limiter.acquire("alice"));
        assertThat(refused.getRetryAfterSeconds()).isEqualTo(20); // one token per 20 s
        assertThat(refused.headers()).containsEntry("Retry-After", "20");
    }

    @Test
    void tokensComeBackEvenlyAndNeverAboveCapacity() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("alice");
        }
        clock.advance(Duration.ofSeconds(19));
        assertThat(catchThrowableOfType(RateLimitedException.class, () -> limiter.acquire("alice")).getRetryAfterSeconds())
                .isEqualTo(1);
        clock.advance(Duration.ofSeconds(1));
        assertThatCode(() -> limiter.acquire("alice")).doesNotThrowAnyException();

        clock.advance(Duration.ofHours(1)); // a long pause refills to the capacity, not beyond
        for (int i = 0; i < 3; i++) {
            limiter.acquire("alice");
        }
        assertThat(catchThrowableOfType(RateLimitedException.class, () -> limiter.acquire("alice"))).isNotNull();
    }

    @Test
    void usersHaveTheirOwnBudget() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("alice");
        }
        assertThatCode(() -> limiter.acquire("bob")).doesNotThrowAnyException();
    }
}
