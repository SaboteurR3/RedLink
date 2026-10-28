package ge.tsu.redlink.missioncontrol;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Replaces the system clock with a {@link MutableClock} starting at {@link #T0}, for every bean that
 * reads time (commands, uplink, link signer).
 */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {

    public static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
