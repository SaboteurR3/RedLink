package ge.tsu.redlink.roversim.security;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.roversim.web.ProblemResponses;
import ge.tsu.redlink.roversim.web.ProblemTypes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.io.IOException;
import java.time.Clock;

/**
 * The rover has no users. Earth reaches it only through signed link packets on {@code /flight/**};
 * apart from health and info for monitoring, nothing else exists from the outside.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LinkProperties.class)
public class RoverSecurityConfig {

    @Bean
    PacketSigner packetSigner(LinkProperties properties, Clock clock) {
        byte[] secret = Base64Keys.decode(properties.secret(), "redlink.link.secret");
        return new PacketSigner(secret, clock, properties.signatureTolerance());
    }

    @Bean
    @Order(1)
    SecurityFilterChain linkSecurity(HttpSecurity http, PacketSigner packetSigner, LinkProperties properties,
                                     ProblemResponses problems) throws Exception {
        var filter = new LinkSignatureFilter(packetSigner, Math.toIntExact(properties.maxPacketSize().toBytes()), problems);
        http
                .securityMatcher("/flight/**")
                .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(LinkSignatureFilter.LINK_ROLE))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, e) ->
                        problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_LINK_SIGNATURE,
                                "Invalid link signature", "The packet is not signed by a trusted link endpoint")))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    /**
     * Everything that is not the link: health and info are public, the rest answers 404 so the
     * rover does not even reveal which paths exist.
     */
    @Bean
    @Order(2)
    SecurityFilterChain everythingElse(HttpSecurity http, ProblemResponses problems) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Error dispatches of link requests land here; denying them would turn a 500 into a 404.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> notFound(problems, request, response))
                        .accessDeniedHandler((request, response, e) -> notFound(problems, request, response)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    private static void notFound(ProblemResponses problems, HttpServletRequest request,
                                 HttpServletResponse response) throws IOException {
        problems.write(request, response, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found",
                "No such endpoint");
    }
}
