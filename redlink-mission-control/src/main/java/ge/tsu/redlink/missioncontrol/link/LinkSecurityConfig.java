package ge.tsu.redlink.missioncontrol.link;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.missioncontrol.security.Base64Keys;
import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

/**
 * {@code /dsn/**} is where rover-sim delivers telemetry. Only HMAC-signed packets get through;
 * user tokens are not accepted here, and link packets are not accepted anywhere else.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LinkProperties.class)
public class LinkSecurityConfig {

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
                .securityMatcher("/dsn/**")
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
}
