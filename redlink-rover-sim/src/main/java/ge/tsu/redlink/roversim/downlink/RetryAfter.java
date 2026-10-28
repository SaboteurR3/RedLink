package ge.tsu.redlink.roversim.downlink;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Reads a {@code Retry-After} header (RFC 9110 §10.2.3): either delay-seconds or an HTTP-date.
 * Anything unparseable is ignored (the sender's own backoff applies), and a hint is never followed
 * further than {@link #MAX}, so a broken or hostile server cannot park the downlink for days.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class RetryAfter {

    static final Duration MAX = Duration.ofHours(1);

    static Optional<Duration> parse(String header, Instant now) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        Duration delay;
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                delay = Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException e) {
                delay = MAX; // more digits than a long: "very long", which the cap turns into MAX
            }
        } else {
            try {
                delay = Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        return Optional.of(delay.compareTo(MAX) > 0 ? MAX : delay);
    }
}
