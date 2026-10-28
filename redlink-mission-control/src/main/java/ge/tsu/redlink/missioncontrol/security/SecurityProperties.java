package ge.tsu.redlink.missioncontrol.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * {@code redlink.security.*}. Validated at startup, so a missing secret stops the app instead of
 * failing on the first request.
 */
@Validated
@ConfigurationProperties("redlink.security")
public record SecurityProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull LoginThrottle loginThrottle,
        @Valid @NotNull Cors cors) {

    /**
     * @param secret   base64-encoded HMAC key, at least 32 bytes
     * @param issuer   {@code iss} claim written and required
     * @param audience {@code aud} claim written and required
     * @param ttl      access token lifetime
     */
    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration ttl) {
    }

    /**
     * @param maxFailures failed logins allowed per (username, client IP) within {@code window}
     */
    public record LoginThrottle(@Min(1) int maxFailures, @NotNull Duration window) {
    }

    /**
     * @param allowedOrigins exact origins allowed to call the API from a browser; empty = none
     */
    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of()
                    : allowedOrigins.stream().filter(origin -> !origin.isBlank()).toList();
        }
    }
}
