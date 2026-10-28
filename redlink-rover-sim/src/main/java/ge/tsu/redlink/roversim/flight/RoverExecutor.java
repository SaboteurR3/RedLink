package ge.tsu.redlink.roversim.flight;

import ge.tsu.redlink.roversim.rover.RoverStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CommandStepper} for every rover on a fixed delay. Fixed delay (not rate), so a slow
 * tick never overlaps the next one. Off when {@code redlink.rover.executor-enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "redlink.rover.executor-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class RoverExecutor implements SchedulingConfigurer {

    private final RoverStateRepository rovers;
    private final CommandStepper stepper;
    private final RoverProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::tick, properties.tick());
    }

    void tick() {
        for (Long roverId : rovers.findAllIds()) {
            try {
                stepper.advance(roverId);
            } catch (OptimisticLockingFailureException e) {
                log.debug("Rover {} was changed concurrently; retrying next tick", roverId);
            } catch (RuntimeException e) {
                // One broken rover must not stop the others.
                log.error("Executor failed for rover {}", roverId, e);
            }
        }
    }
}
