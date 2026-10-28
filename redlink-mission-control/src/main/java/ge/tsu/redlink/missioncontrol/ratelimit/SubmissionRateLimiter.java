package ge.tsu.redlink.missioncontrol.ratelimit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how fast one user can send commands and missions: a token bucket per user. A burst up to
 * the capacity is fine (a quick plan of several commands); a script hammering the endpoint gets
 * 429 with {@code Retry-After} instead of filling the uplink queue.
 *
 * <p>In-memory: each instance limits on its own (ticket 34 is about sharing state between instances).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class SubmissionRateLimiter {

    /** Above this many tracked users, full buckets are dropped so memory stays bounded. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final RateLimitProperties.Limit limit;
    private final Clock clock;
    private final double tokensPerNano;

    public SubmissionRateLimiter(RateLimitProperties properties, Clock clock) {
        this.limit = properties.submissions();
        this.clock = clock;
        this.tokensPerNano = limit.capacity() / (double) limit.per().toNanos();
    }

    /**
     * Takes one token for this user.
     *
     * @throws RateLimitedException if the user has none left
     */
    public void acquire(String user) {
        long now = nanos();
        Bucket after = buckets.compute(user, (key, bucket) -> {
            double tokens = bucket == null ? limit.capacity() : refilled(bucket, now);
            return tokens >= 1 ? new Bucket(tokens - 1, now, true) : new Bucket(tokens, now, false);
        });
        if (!after.granted()) {
            long waitNanos = (long) Math.ceil((1 - after.tokens()) / tokensPerNano);
            throw new RateLimitedException(limit.capacity(), describe(limit.per()),
                    Math.max(1, (waitNanos + 999_999_999) / 1_000_000_000)); // whole seconds, rounded up
        }
        if (buckets.size() > PURGE_THRESHOLD) {
            buckets.entrySet().removeIf(entry -> refilled(entry.getValue(), now) >= limit.capacity());
        }
    }

    private double refilled(Bucket bucket, long now) {
        return Math.min(limit.capacity(), bucket.tokens() + (now - bucket.at()) * tokensPerNano);
    }

    private long nanos() {
        Instant now = clock.instant();
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    private static String describe(Duration per) {
        return per.toSeconds() % 60 == 0 ? per.toMinutes() + " min" : per.toSeconds() + " s";
    }

    private record Bucket(double tokens, long at, boolean granted) {
    }
}
