package ge.tsu.redlink.missioncontrol.uplink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs transmission and delivery on a fixed delay. Off when {@code redlink.uplink.enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "redlink.uplink.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class UplinkScheduler implements SchedulingConfigurer {

    private final UplinkTransmitter transmitter;
    private final UplinkDispatcher dispatcher;
    private final UplinkProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::round, properties.pollInterval());
    }

    private void round() {
        try {
            while (transmitter.transmitDue() == properties.batchSize()) {
                log.debug("Full batch transmitted, continuing");
            }
        } catch (OptimisticLockingFailureException e) {
            log.debug("A command changed during transmission (cancelled?); next round picks up the rest");
        } catch (RuntimeException e) {
            log.error("Uplink transmission failed", e);
        }
        try {
            while (dispatcher.dispatchDue() == properties.batchSize()) {
                log.debug("Full batch delivered, continuing");
            }
        } catch (RuntimeException e) {
            log.error("Uplink delivery failed", e);
        }
    }
}
