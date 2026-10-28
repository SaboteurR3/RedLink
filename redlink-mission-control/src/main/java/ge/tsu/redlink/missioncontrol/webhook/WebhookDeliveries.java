package ge.tsu.redlink.missioncontrol.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Delivery outbox bookkeeping in single SQL statements, same design as the link outboxes (SPEC §4):
 * claim due rows with a lease, send outside any transaction, record the outcome.
 */
@Repository
@RequiredArgsConstructor
class WebhookDeliveries {

    private final JdbcClient jdbc;

    /** Reserves due deliveries until {@code leaseUntil}, together with their webhook's URL and secret. */
    List<ClaimedDelivery> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE webhook_deliveries
                            SET next_attempt_at = :leaseUntil, attempts = attempts + 1
                            WHERE id IN (
                                SELECT id FROM webhook_deliveries
                                WHERE status = 'PENDING' AND next_attempt_at <= :now
                                ORDER BY next_attempt_at, created_at
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED)
                            RETURNING id, webhook_id, event, payload, attempts, created_at)
                        SELECT c.id, c.webhook_id, c.event, c.payload, c.attempts, c.created_at, w.url, w.secret, w.active
                        FROM claimed c JOIN webhooks w ON w.id = c.webhook_id""")
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedDelivery(
                        rs.getObject("id", UUID.class),
                        rs.getObject("webhook_id", UUID.class),
                        rs.getString("event"),
                        rs.getString("payload"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("url"),
                        rs.getString("secret"),
                        rs.getBoolean("active")))
                .list()
                .stream()
                .sorted(Comparator.comparing(ClaimedDelivery::createdAt))
                .toList();
    }

    void markDelivered(UUID id, int statusCode, Instant now) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'DELIVERED', delivered_at = ?, last_status_code = ?, last_error = NULL "
                        + "WHERE id = ? AND status = 'PENDING'")
                .params(Timestamp.from(now), statusCode, id).update();
    }

    void scheduleRetry(UUID id, Instant nextAttemptAt, Integer statusCode, String error) {
        jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = ?, last_status_code = ?, last_error = ? "
                        + "WHERE id = ? AND status = 'PENDING'")
                .params(Timestamp.from(nextAttemptAt), statusCode, truncate(error), id).update();
    }

    void markFailed(UUID id, Integer statusCode, String error) {
        jdbc.sql("UPDATE webhook_deliveries SET status = 'FAILED', last_status_code = ?, last_error = ? "
                        + "WHERE id = ? AND status = 'PENDING'")
                .params(statusCode, truncate(error), id).update();
    }

    void deactivateWebhook(UUID webhookId) {
        jdbc.sql("UPDATE webhooks SET active = FALSE WHERE id = ?").param(webhookId).update();
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }

    /**
     * @param attempts including this one
     */
    record ClaimedDelivery(UUID id, UUID webhookId, String event, String payload, int attempts, Instant createdAt,
                           String url, String secret, boolean webhookActive) {

        @Override
        public String toString() {
            return "ClaimedDelivery[id=" + id + ", event=" + event + ", url=" + url + ", secret=***]";
        }
    }
}
