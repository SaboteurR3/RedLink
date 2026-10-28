package ge.tsu.redlink.missioncontrol;

import ge.tsu.redlink.missioncontrol.auth.TokenService;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;

/**
 * Valid access tokens for tests, signed with the application's own key.
 */
public final class TestTokens {

    private TestTokens() {
    }

    /** {@code "Bearer <jwt>"} for a user whose name is the lower-cased role. */
    public static String bearer(JwtEncoder encoder, String role) {
        return bearer(encoder, role, role.toLowerCase());
    }

    /** {@code "Bearer <jwt>"} for a named user with one role. */
    public static String bearer(JwtEncoder encoder, String role, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("redlink-mission-control")
                .audience(List.of("redlink-api"))
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(TokenService.ROLES_CLAIM, List.of(role))
                .build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
