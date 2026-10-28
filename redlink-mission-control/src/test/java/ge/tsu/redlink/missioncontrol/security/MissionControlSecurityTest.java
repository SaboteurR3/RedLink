package ge.tsu.redlink.missioncontrol.security;

import com.jayway.jsonpath.JsonPath;
import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.missioncontrol.TimeConfig;
import ge.tsu.redlink.missioncontrol.auth.AuthController;
import ge.tsu.redlink.missioncontrol.auth.AuthService;
import ge.tsu.redlink.missioncontrol.auth.LoginThrottle;
import ge.tsu.redlink.missioncontrol.auth.TokenService;
import ge.tsu.redlink.missioncontrol.events.StreamTickets;
import ge.tsu.redlink.missioncontrol.link.LinkSecurityConfig;
import ge.tsu.redlink.missioncontrol.user.Role;
import ge.tsu.redlink.missioncontrol.user.UserAccount;
import ge.tsu.redlink.missioncontrol.user.UserAccountDetailsService;
import ge.tsu.redlink.missioncontrol.user.UserAccountRepository;
import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole security setup through real HTTP requests (MockMvc): login, tokens, roles, CORS,
 * headers and link authentication. The user table is mocked, so no database is needed.
 */
@WebMvcTest(controllers = {AuthController.class, MissionControlSecurityTest.ProbeController.class})
@Import({SecurityConfig.class, LinkSecurityConfig.class, TimeConfig.class, ProblemResponses.class,
        TokenService.class, AuthService.class, LoginThrottle.class, UserAccountDetailsService.class, StreamTickets.class,
        // Explicit: Boot's test scanning skips classes nested in test classes.
        MissionControlSecurityTest.ProbeController.class})
@ActiveProfiles("test")
class MissionControlSecurityTest {

    private static final String PASSWORD = "correct horse battery staple";
    // Hashing is deliberately slow, so hash once for all tests.
    private static final String PASSWORD_HASH = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    PacketSigner packetSigner;

    @MockitoBean
    UserAccountRepository users;

    @BeforeEach
    void users() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        for (Role role : Role.values()) {
            String username = role.name().toLowerCase();
            when(users.findByUsername(username))
                    .thenReturn(Optional.of(new UserAccount(username, PASSWORD_HASH, role, Instant.now())));
        }
    }

    @Nested
    class Login {

        @Test
        void validCredentialsReturnAnUncacheableBearerToken() throws Exception {
            mvc.perform(login("operator", PASSWORD))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.token_type").value("Bearer"))
                    .andExpect(jsonPath("$.expires_in").value(900))
                    .andExpect(jsonPath("$.access_token").isNotEmpty());
        }

        @Test
        void issuedTokenIdentifiesTheUser() throws Exception {
            String token = loginAndGetToken("Operator"); // usernames are case-insensitive

            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("operator"))
                    .andExpect(jsonPath("$.roles[0]").value("OPERATOR"));
        }

        @Test
        void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
            String wrongPassword = mvc.perform(login("operator", "nope").with(clientIp("10.0.0.1")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-credentials"))
                    .andReturn().getResponse().getContentAsString();
            String unknownUser = mvc.perform(login("nobody", "nope").with(clientIp("10.0.0.1")))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(unknownUser).isEqualTo(wrongPassword);
        }

        @Test
        void sixthFailedAttemptIsThrottledEvenWithTheRightPassword() throws Exception {
            RequestPostProcessor attacker = clientIp("10.0.0.66");
            for (int i = 0; i < 5; i++) {
                mvc.perform(login("viewer", "guess-" + i).with(attacker)).andExpect(status().isUnauthorized());
            }

            mvc.perform(login("viewer", "guess-6").with(attacker))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/too-many-login-attempts"));
            mvc.perform(login("viewer", PASSWORD).with(attacker)).andExpect(status().isTooManyRequests());
        }

        @Test
        void throttlingIsPerClientSoTheRealUserIsNotLockedOut() throws Exception {
            RequestPostProcessor attacker = clientIp("10.0.0.77");
            for (int i = 0; i < 6; i++) {
                mvc.perform(login("flight_director", "guess-" + i).with(attacker));
            }

            mvc.perform(login("flight_director", PASSWORD).with(clientIp("10.0.0.78"))).andExpect(status().isOk());
        }

        @Test
        void invalidBodyIsBadRequest() throws Exception {
            mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class BearerTokens {

        @Test
        void missingTokenIs401WithBearerChallenge() throws Exception {
            mvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/unauthenticated"));
        }

        @Test
        void tamperedTokenIsRejected() throws Exception {
            String token = token("OPERATOR", claims -> {
            });
            String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

            expectRejected(tampered);
        }

        @Test
        void expiredTokenIsRejected() throws Exception {
            expectRejected(token("OPERATOR", claims -> claims
                    .issuedAt(Instant.now().minusSeconds(3600))
                    .expiresAt(Instant.now().minusSeconds(600))));
        }

        @Test
        void tokenFromAnotherIssuerIsRejected() throws Exception {
            expectRejected(token("OPERATOR", claims -> claims.issuer("someone-else")));
        }

        @Test
        void tokenForAnotherAudienceIsRejected() throws Exception {
            expectRejected(token("OPERATOR", claims -> claims.audience(List.of("another-api"))));
        }

        @Test
        void unsignedTokenIsRejected() throws Exception {
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            String header = base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            String payload = base64.encodeToString(("{\"iss\":\"redlink-mission-control\",\"aud\":\"redlink-api\","
                    + "\"sub\":\"mallory\",\"roles\":[\"FLIGHT_DIRECTOR\"],\"exp\":4102444800}").getBytes(StandardCharsets.UTF_8));

            expectRejected(header + "." + payload + ".");
        }

        private void expectRejected(String token) throws Exception {
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("invalid_token")));
        }
    }

    @Nested
    class Roles {

        @Test
        void viewerCanReadButNotCommand() throws Exception {
            String viewer = token("VIEWER", claims -> {
            });

            mvc.perform(get("/api/v1/test/viewer").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/test/operator").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/forbidden"));
        }

        @Test
        void operatorCanCommandButNotApprove() throws Exception {
            String operator = token("OPERATOR", claims -> {
            });

            mvc.perform(get("/api/v1/test/viewer").header(HttpHeaders.AUTHORIZATION, "Bearer " + operator))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/test/operator").header(HttpHeaders.AUTHORIZATION, "Bearer " + operator))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/test/director").header(HttpHeaders.AUTHORIZATION, "Bearer " + operator))
                    .andExpect(status().isForbidden());
        }

        @Test
        void flightDirectorInheritsOperatorRights() throws Exception {
            String director = token("FLIGHT_DIRECTOR", claims -> {
            });

            mvc.perform(post("/api/v1/test/operator").header(HttpHeaders.AUTHORIZATION, "Bearer " + director))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/v1/test/director").header(HttpHeaders.AUTHORIZATION, "Bearer " + director))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Browser {

        @Test
        void preflightFromTheFrontendOriginIsAllowed() throws Exception {
            mvc.perform(options("/api/v1/auth/token")
                            .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        }

        @Test
        void preflightFromAnotherOriginIsRejected() throws Exception {
            mvc.perform(options("/api/v1/auth/token")
                            .header(HttpHeaders.ORIGIN, "http://evil.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        void responsesCarrySecurityHeaders() throws Exception {
            mvc.perform(get("/api/v1/auth/me"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
        }

        @Test
        void protectedResourceMetadataIsPublicAndAccurate() throws Exception {
            mvc.perform(get("/.well-known/oauth-protected-resource"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
        }
    }

    @Nested
    class DeepSpaceLink {

        private static final String PACKET = "{\"packetId\":\"p-1\"}";

        @Test
        void signedPacketPassesAndBodyIsStillReadable() throws Exception {
            mvc.perform(post("/dsn/test-echo")
                            .header(PacketSigner.HEADER, packetSigner.sign(PACKET.getBytes(StandardCharsets.UTF_8)))
                            .contentType(MediaType.APPLICATION_JSON).content(PACKET))
                    .andExpect(status().isOk())
                    .andExpect(content().string(PACKET));
        }

        @Test
        void unsignedPacketIsRejected() throws Exception {
            mvc.perform(post("/dsn/test-echo").contentType(MediaType.APPLICATION_JSON).content(PACKET))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-link-signature"));
        }

        @Test
        void tamperedPacketIsRejected() throws Exception {
            mvc.perform(post("/dsn/test-echo")
                            .header(PacketSigner.HEADER, packetSigner.sign(PACKET.getBytes(StandardCharsets.UTF_8)))
                            .contentType(MediaType.APPLICATION_JSON).content(PACKET.replace("p-1", "p-2")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString("MISMATCH"))));
        }

        @Test
        void userTokensAreNotAcceptedOnTheLink() throws Exception {
            mvc.perform(post("/dsn/test-echo")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("FLIGHT_DIRECTOR", claims -> {
                            }))
                            .contentType(MediaType.APPLICATION_JSON).content(PACKET))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void oversizedPacketIsRejectedBeforeVerification() throws Exception {
            byte[] big = new byte[2048];
            mvc.perform(post("/dsn/test-echo")
                            .header(PacketSigner.HEADER, packetSigner.sign(big))
                            .contentType(MediaType.APPLICATION_JSON).content(big))
                    .andExpect(status().isPayloadTooLarge());
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private String loginAndGetToken(String username) throws Exception {
        String body = mvc.perform(login(username, PASSWORD)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    private static RequestPostProcessor clientIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** A token signed with the real key; {@code customize} can break individual claims. */
    private String token(String role, Consumer<JwtClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("redlink-mission-control")
                .audience(List.of("redlink-api"))
                .subject(role.toLowerCase())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim(TokenService.ROLES_CLAIM, List.of(role));
        customize.accept(claims);
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/test/viewer")
        @PreAuthorize("hasRole('VIEWER')")
        String viewer() {
            return "ok";
        }

        @PostMapping("/api/v1/test/operator")
        @PreAuthorize("hasRole('OPERATOR')")
        String operator() {
            return "ok";
        }

        @PostMapping("/api/v1/test/director")
        @PreAuthorize("hasRole('FLIGHT_DIRECTOR')")
        String director() {
            return "ok";
        }

        @PostMapping("/dsn/test-echo")
        String echo(@RequestBody String body) {
            return body;
        }
    }
}
