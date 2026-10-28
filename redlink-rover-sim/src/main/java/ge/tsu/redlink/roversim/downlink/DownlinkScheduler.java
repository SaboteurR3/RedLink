package ge.tsu.redlink.roversim.downlink;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the dispatcher on a fixed delay. Off when {@code redlink.downlink.dispatcher-enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "redlink.downlink.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class DownlinkScheduler implements SchedulingConfigurer {

    private final DownlinkDispatcher dispatcher;
    private final DownlinkProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::poll, properties.pollInterval());
    }

    private void poll() {
        try {
            // Drain everything that is due, one batch at a time.
            while (dispatcher.dispatchDue() == properties.batchSize()) {
                log.debug("Full batch sent, polling again");
            }
        } catch (RuntimeException e) {
            log.error("Downlink dispatch failed", e);
        }
    }
}
