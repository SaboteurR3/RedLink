package ge.tsu.redlink.missioncontrol.idempotency;

import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyExceptions.IdempotencyKeyInProgressException;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyExceptions.IdempotencyKeyReusedException;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyExceptions.InvalidIdempotencyKeyException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Makes a non-idempotent request (POST) safe to retry: the first request with a key runs, every
 * later one with the same key and the same content gets the first one's response.
 *
 * <p>Everything happens in the caller's transaction. The key row is inserted first
 * ({@code ON CONFLICT DO NOTHING}); a concurrent duplicate blocks on that row until the first
 * transaction ends and then replays its response. If the first request fails, its transaction
 * rolls back and the key is forgotten, so only successful responses are ever replayed.
 */
@Service
@EnableConfigurationProperties(IdempotencyProperties.class)
@RequiredArgsConstructor
public class IdempotencyService {

    /** Visible ASCII, 1–255 characters (UUIDs recommended). */
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final IdempotencyProperties properties;

    /**
     * @throws InvalidIdempotencyKeyException if the key is missing or malformed
     */
    public static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new InvalidIdempotencyKeyException(
                    "This request needs an Idempotency-Key header (e.g. a fresh UUID) so it can be retried safely");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key must be 1–255 visible ASCII characters");
        }
    }

    /** SHA-256 over the parts that identify a request (method, path, canonical body). */
    public static String fingerprint(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0); // separator, so ("ab","c") ≠ ("a","bc")
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Runs {@code action} once per (user, key) and stores its response, or replays the stored one.
     *
     * @param requestHash {@link #fingerprint} of the request; a different hash under the same key is an error
     * @throws IdempotencyKeyReusedException if the key was used for a different request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome execute(String user, String key, String requestHash, Supplier<StoredResponse> action) {
        Instant now = clock.instant();
        // An expired key is forgotten, so it may start a new request.
        jdbc.sql("DELETE FROM idempotency_records WHERE username = ? AND idempotency_key = ? AND created_at < ?")
                .params(user, key, Timestamp.from(now.minus(properties.retention()))).update();

        int claimed = jdbc.sql("""
                        INSERT INTO idempotency_records (username, idempotency_key, request_hash, created_at)
                        VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                .params(user, key, requestHash, Timestamp.from(now)).update();

        if (claimed == 0) {
            return new Outcome(replay(user, key, requestHash), true);
        }
        StoredResponse response = action.get();
        jdbc.sql("""
                        UPDATE idempotency_records SET response_status = ?, response_location = ?, response_body = ?
                        WHERE username = ? AND idempotency_key = ?""")
                .params(response.status(), response.location(), response.body(), user, key).update();
        return new Outcome(response, false);
    }

    private StoredResponse replay(String user, String key, String requestHash) {
        Stored stored = jdbc.sql("""
                        SELECT request_hash, response_status, response_location, response_body
                        FROM idempotency_records WHERE username = ? AND idempotency_key = ?""")
                .params(user, key)
                .query((rs, row) -> new Stored(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4)))
                .optional()
                // Vanishing between our INSERT and SELECT means the other request rolled back just now.
                .orElseThrow(IdempotencyKeyInProgressException::new);
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return Optional.ofNullable(stored.status())
                .map(status -> new StoredResponse(status, stored.location(), stored.body()))
                .orElseThrow(IdempotencyKeyInProgressException::new);
    }

    /** Deletes expired keys. */
    @Transactional
    public int purgeExpired() {
        return jdbc.sql("DELETE FROM idempotency_records WHERE created_at < ?")
                .param(Timestamp.from(clock.instant().minus(properties.retention()))).update();
    }

    /** What is replayed for a repeated request: status, Location and the exact body text. */
    public record StoredResponse(int status, String location, String body) {
    }

    public record Outcome(StoredResponse response, boolean replayed) {
    }

    private record Stored(String requestHash, Integer status, String location, String body) {
    }
}
