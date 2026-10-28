package ge.tsu.redlink.roversim.downlink;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void delaySeconds() {
        assertThat(RetryAfter.parse("30", NOW)).contains(Duration.ofSeconds(30));
        assertThat(RetryAfter.parse(" 0 ", NOW)).contains(Duration.ZERO);
    }

    @Test
    void httpDate() {
        assertThat(RetryAfter.parse("Mon, 26 Oct 2026 12:02:00 GMT", NOW)).contains(Duration.ofMinutes(2));
        assertThat(RetryAfter.parse("Mon, 26 Oct 2026 11:00:00 GMT", NOW)).as("in the past").contains(Duration.ZERO);
    }

    @Test
    void cappedAtOneHour() {
        assertThat(RetryAfter.parse("86400", NOW)).contains(RetryAfter.MAX);
        assertThat(RetryAfter.parse("99999999999999999999999", NOW)).contains(RetryAfter.MAX);
    }

    @Test
    void garbageIsIgnored() {
        assertThat(RetryAfter.parse(null, NOW)).isEmpty();
        assertThat(RetryAfter.parse("", NOW)).isEmpty();
        assertThat(RetryAfter.parse("soon", NOW)).isEmpty();
        assertThat(RetryAfter.parse("-5", NOW)).isEmpty();
    }
}
