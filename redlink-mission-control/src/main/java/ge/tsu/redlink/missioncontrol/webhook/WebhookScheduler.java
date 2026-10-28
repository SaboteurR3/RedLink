package ge.tsu.redlink.missioncontrol.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Runs the webhook dispatcher on a fixed delay. Off when {@code redlink.webhooks.dispatcher-enabled=false}.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "redlink.webhooks.dispatcher-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
class WebhookScheduler implements SchedulingConfigurer {

    private final WebhookDispatcher dispatcher;
    private final WebhookProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::poll, properties.pollInterval());
    }

    private void poll() {
        try {
            while (dispatcher.dispatchDue() == properties.batchSize()) {
                log.debug("Full batch of webhook deliveries, continuing");
            }
        } catch (RuntimeException e) {
            log.error("Webhook dispatch failed", e);
        }
    }
}
