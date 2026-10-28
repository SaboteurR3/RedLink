package ge.tsu.redlink.missioncontrol.security;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Base64;

/**
 * Decodes base64 secrets from configuration and enforces a minimum strength.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Base64Keys {

    /** 256 bits: the key size of HMAC-SHA256. */
    public static final int MIN_BYTES = 32;

    public static byte[] decode(String base64, String propertyName) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(propertyName + " must be base64-encoded", e);
        }
        if (key.length < MIN_BYTES) {
            throw new IllegalStateException(propertyName + " must decode to at least " + MIN_BYTES
                    + " bytes (generate one with: openssl rand -base64 32)");
        }
        return key;
    }
}
