package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.missioncontrol.webhook.WebhookDeliveries.ClaimedDelivery;
import ge.tsu.redlink.missioncontrol.webhook.WebhookSender.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Delivers queued webhook notifications, at least once. Unlike the deep-space link it gives up
 * after {@code max-attempts}: the receiver is someone else's server, and the delivery log shows
 * what happened.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookDispatcher {

    private final WebhookDeliveries deliveries;
    private final WebhookSender sender;
    private final WebhookProperties properties;
    private final Clock clock;

    /**
     * @return number of deliveries attempted
     */
    public int dispatchDue() {
        Instant now = now();
        List<ClaimedDelivery> batch = deliveries.claimDue(now, now.plus(properties.lease()), properties.batchSize());
        for (ClaimedDelivery delivery : batch) {
            if (!delivery.webhookActive()) {
                deliveries.markFailed(delivery.id(), null, "Webhook is deactivated");
                continue;
            }
            Result result = sender.send(delivery);
            switch (result.outcome()) {
                case DELIVERED -> deliveries.markDelivered(delivery.id(), result.statusCode(), now());
                case GONE -> {
                    deliveries.markFailed(delivery.id(), result.statusCode(), result.error());
                    deliveries.deactivateWebhook(delivery.webhookId());
                    log.info("Webhook {} answered 410 Gone and was deactivated", delivery.webhookId());
                }
                case RETRY -> {
                    if (delivery.attempts() >= properties.maxAttempts()) {
                        deliveries.markFailed(delivery.id(), result.statusCode(), result.error());
                        log.warn("Webhook delivery {} gave up after {} attempts: {}", delivery.id(), delivery.attempts(), result.error());
                    } else {
                        deliveries.scheduleRetry(delivery.id(), now().plus(properties.backoff(delivery.attempts())),
                                result.statusCode(), result.error());
                    }
                }
            }
        }
        return batch.size();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
