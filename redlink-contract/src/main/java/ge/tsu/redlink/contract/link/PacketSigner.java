package ge.tsu.redlink.contract.link;

import ge.tsu.redlink.contract.link.SignatureVerificationException.Reason;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Authenticates link packets with HMAC-SHA256, like real missions authenticate uplinked commands.
 *
 * <p>Header format: {@code X-Link-Signature: t=<epoch seconds>,v1=<base64url HMAC>} where the
 * HMAC covers {@code "<t>." + body}. Signing the exact body bytes means the receiver must verify
 * the raw request body before parsing it. The timestamp limits how long a captured packet can be
 * replayed; replays inside the window are dropped by packet-id dedupe on the receiver.
 *
 * <p>Thread-safe.
 */
public final class PacketSigner {

    public static final String HEADER = "X-Link-Signature";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final String TIMESTAMP_PREFIX = "t=";
    private static final String SIGNATURE_PREFIX = "v1=";

    private final SecretKeySpec key;
    private final Clock clock;
    private final Duration tolerance;

    /**
     * @param secret    shared link secret, at least 32 bytes (256 bits, the HMAC-SHA256 key size)
     * @param clock     time source, injectable for tests
     * @param tolerance how far a packet timestamp may be from now, in either direction
     */
    public PacketSigner(byte[] secret, Clock clock, Duration tolerance) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("Link secret must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        if (tolerance.isNegative() || tolerance.isZero()) {
            throw new IllegalArgumentException("Tolerance must be positive");
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tolerance = tolerance;
    }

    /**
     * @return the value for the {@value #HEADER} header
     */
    public String sign(byte[] body) {
        long timestamp = clock.instant().getEpochSecond();
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(timestamp, body));
        return TIMESTAMP_PREFIX + timestamp + "," + SIGNATURE_PREFIX + signature;
    }

    /**
     * @throws SignatureVerificationException if the header is missing, malformed, does not match
     *                                        the body, or is outside the tolerance window
     */
    public void verify(String header, byte[] body) {
        if (header == null || header.isBlank()) {
            throw new SignatureVerificationException(Reason.MISSING, "Missing " + HEADER + " header");
        }
        ParsedHeader parsed = parse(header);

        byte[] expected = hmac(parsed.timestamp(), body);
        if (!MessageDigest.isEqual(expected, parsed.signature())) {
            throw new SignatureVerificationException(Reason.MISMATCH, "Signature does not match body");
        }

        Instant signedAt = Instant.ofEpochSecond(parsed.timestamp());
        Duration age = Duration.between(signedAt, clock.instant()).abs();
        if (age.compareTo(tolerance) > 0) {
            throw new SignatureVerificationException(Reason.OUTSIDE_TOLERANCE,
                    "Signature timestamp " + signedAt + " is outside the " + tolerance + " tolerance");
        }
    }

    private byte[] hmac(long timestamp, byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is mandatory on every Java platform, so this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    private static ParsedHeader parse(String header) {
        Long timestamp = null;
        byte[] signature = null;
        for (String part : header.split(",")) {
            String trimmed = part.trim();
            try {
                if (trimmed.startsWith(TIMESTAMP_PREFIX) && timestamp == null) {
                    timestamp = Long.parseLong(trimmed.substring(TIMESTAMP_PREFIX.length()));
                } else if (trimmed.startsWith(SIGNATURE_PREFIX) && signature == null) {
                    signature = Base64.getUrlDecoder().decode(trimmed.substring(SIGNATURE_PREFIX.length()));
                } else {
                    throw malformed();
                }
            } catch (IllegalArgumentException e) { // also covers NumberFormatException
                throw malformed();
            }
        }
        if (timestamp == null || signature == null) {
            throw malformed();
        }
        return new ParsedHeader(timestamp, signature);
    }

    private static SignatureVerificationException malformed() {
        return new SignatureVerificationException(Reason.MALFORMED, "Malformed " + HEADER + " header");
    }

    private record ParsedHeader(long timestamp, byte[] signature) {
    }
}
