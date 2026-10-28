package ge.tsu.redlink.roversim.events;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableConfigurationProperties(RoverEventsProperties.class)
class RoverEventsConfig {

    /** Real randomness; tests supply a scripted {@link Dice} marked {@code @Primary}. */
    @Bean
    Dice dice() {
        return () -> ThreadLocalRandom.current().nextDouble();
    }
}
