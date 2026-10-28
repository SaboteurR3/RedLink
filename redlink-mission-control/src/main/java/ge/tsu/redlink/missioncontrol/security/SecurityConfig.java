package ge.tsu.redlink.missioncontrol.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import ge.tsu.redlink.missioncontrol.auth.TokenService;
import ge.tsu.redlink.missioncontrol.events.StreamTicketFilter;
import ge.tsu.redlink.missioncontrol.events.StreamTickets;
import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.List;

/**
 * Security for the public API: stateless JWT bearer authentication, roles, CORS and headers.
 * The deep-space link endpoints have their own chain in {@code LinkSecurityConfig}.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * The OpenAPI document and Swagger UI: public and read-only. The UI is an HTML page with its own
     * scripts and styles, so it gets a CSP that allows same-origin resources instead of the API's
     * deny-all one.
     */
    @Bean
    @Order(0)
    SecurityFilterChain apiDocsSecurity(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                                        + "frame-ancestors 'none'")));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                                    ProblemResponses problems, StreamTickets streamTickets) throws Exception {
        var entryPoint = new ProblemAuthenticationEntryPoint(problems);
        var accessDenied = new ProblemAccessDeniedHandler(problems);
        http
                // Browsers open SSE streams with a single-use ticket instead of a bearer header.
                .addFilterBefore(new StreamTicketFilter(streamTickets, problems), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // The async dispatch that completes an SSE stream belongs to a request that
                        // was already authorized (and whose stream ticket is used up).
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // Any method, so a wrong one gets 405 (Allow: POST) rather than a misleading 401.
                        .requestMatchers("/api/v1/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/.well-known/oauth-protected-resource").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        // Served at /.well-known/oauth-protected-resource (RFC 9728). Tokens are not
                        // bound to client certificates, so don't claim they are.
                        .protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(
                                builder -> builder.tlsClientCertificateBoundAccessTokens(false)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                // Bearer tokens are never sent automatically by a browser, so CSRF does not apply.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> {
                })
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // A JSON API never needs to load scripts, styles or frames.
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    /**
     * FLIGHT_DIRECTOR can do everything an OPERATOR can, who can do everything a VIEWER can.
     * Picked up by both URL and method security.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("FLIGHT_DIRECTOR").implies("OPERATOR")
                .role("OPERATOR").implies("VIEWER")
                .build();
    }

    // --- Tokens ---------------------------------------------------------------------------

    @Bean
    SecretKey jwtSigningKey(SecurityProperties properties) {
        byte[] key = Base64Keys.decode(properties.jwt().secret(), "redlink.security.jwt.secret");
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * Accepts only HS256 tokens signed with our key, from our issuer, for our audience, and not
     * expired (with the default 60 s clock skew).
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        String audience = properties.jwt().audience();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /** {@code "roles": ["OPERATOR"]} becomes the authority {@code ROLE_OPERATOR}. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(TokenService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    // --- Login ----------------------------------------------------------------------------

    /**
     * Delegating encoder: bcrypt today, stored as {@code {bcrypt}...}, so the algorithm can change
     * later while old hashes keep working.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Checks passwords for {@code POST /api/v1/auth/token}; not used for bearer tokens. */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    // --- Browser access -------------------------------------------------------------------

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                HttpHeaders.IF_MATCH, HttpHeaders.IF_NONE_MATCH, "Idempotency-Key", "Last-Event-ID"));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, HttpHeaders.ETAG, HttpHeaders.RETRY_AFTER,
                "Idempotent-Replayed"));
        // Tokens travel in the Authorization header, never in cookies.
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
