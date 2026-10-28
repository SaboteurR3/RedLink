package ge.tsu.redlink.missioncontrol.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Queues webhook deliveries (transactional outbox): one row per active webhook subscribed to the
 * event, written in the caller's transaction, so a notification exists if and only if the change
 * it reports was committed. The dispatcher sends them afterwards.
 */
@Service
@EnableConfigurationProperties(WebhookProperties.class)
@RequiredArgsConstructor
public class WebhookNotifier {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Fans the event out to every subscribed webhook in one statement.
     *
     * @param data what the event is about (the command or rover as the API shows it)
     * @return number of deliveries queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int notify(WebhookEvent event, Object data) {
        Instant now = now();
        return jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, webhook_id, event, payload, next_attempt_at, created_at)
                        SELECT gen_random_uuid(), w.id, :event, :payload, :now, :now
                        FROM webhooks w
                        WHERE w.active AND :event = ANY (w.events)""")
                .param("event", event.wireName())
                .param("payload", payload(event, data, now))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** A test delivery to one webhook. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ping(UUID webhookId, Object data) {
        Instant now = now();
        jdbc.sql("""
                        INSERT INTO webhook_deliveries (id, webhook_id, event, payload, next_attempt_at, created_at)
                        VALUES (gen_random_uuid(), :webhook, :event, :payload, :now, :now)""")
                .param("webhook", webhookId)
                .param("event", WebhookEvent.PING.wireName())
                .param("payload", payload(WebhookEvent.PING, data, now))
                .param("now", Timestamp.from(now))
                .update();
    }

    private String payload(WebhookEvent event, Object data, Instant now) {
        return jsonMapper.writeValueAsString(new WebhookPayload(UUID.randomUUID(), event.wireName(), now, data));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The body every receiver gets.
     *
     * @param eventId    same for all webhooks notified about one occurrence
     * @param occurredAt when Earth learned about it
     */
    public record WebhookPayload(UUID eventId, String event, Instant occurredAt, Object data) {
    }
}
