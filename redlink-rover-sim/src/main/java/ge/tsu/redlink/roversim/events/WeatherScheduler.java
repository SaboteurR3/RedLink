package ge.tsu.redlink.roversim.events;

import ge.tsu.redlink.roversim.rover.RoverStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/** Runs the weather with the flight software (off in tests, which call {@link Weather} themselves). */
@Component
@ConditionalOnProperty(name = "redlink.rover.executor-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class WeatherScheduler implements SchedulingConfigurer {

    private final RoverStateRepository rovers;
    private final Weather weather;
    private final RoverEventsProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::tick, properties.weatherInterval());
    }

    void tick() {
        for (Long roverId : rovers.findAllIds()) {
            try {
                weather.update(roverId);
            } catch (OptimisticLockingFailureException e) {
                log.debug("Rover {} was changed concurrently; weather retries next time", roverId);
            } catch (RuntimeException e) {
                log.error("Weather failed for rover {}", roverId, e);
            }
        }
    }
}
