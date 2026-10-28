package ge.tsu.redlink.missioncontrol.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Request and response bodies of {@link AuthController}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class AuthDtos {

    record TokenRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "TokenRequest[username=" + username + ", password=***]";
        }
    }

    /** Field names follow OAuth 2.0 (RFC 6749 §5.1), which clients and tools already understand. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[access_token=***, token_type=" + tokenType + ", expires_in=" + expiresIn + "]";
        }
    }

    record MeResponse(String username, List<String> roles, Instant tokenExpiresAt) {
    }
}
