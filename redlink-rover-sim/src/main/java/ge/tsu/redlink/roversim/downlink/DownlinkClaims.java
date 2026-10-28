package ge.tsu.redlink.roversim.downlink;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Outbox bookkeeping in single SQL statements (auto-commit): claiming due packets with a lease, and
 * recording what happened to them. Plain JDBC because JPA cannot express
 * {@code UPDATE ... RETURNING} with {@code SKIP LOCKED}.
 */
@Repository
@RequiredArgsConstructor
public class DownlinkClaims {

    private final JdbcClient jdbc;

    /**
     * Reserves up to {@code limit} due packets until {@code leaseUntil}. {@code SKIP LOCKED} lets
     * concurrent dispatchers pass over rows another one is claiming, so no packet is claimed twice;
     * moving {@code next_attempt_at} to the lease end keeps it reserved after the statement commits.
     *
     * @return the claimed packets, oldest first
     */
    public List<ClaimedPacket> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE downlink_packets
                        SET next_attempt_at = :leaseUntil, attempts = attempts + 1
                        WHERE packet_id IN (
                            SELECT packet_id FROM downlink_packets
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at, created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING packet_id, packet::text AS packet, attempts, created_at,
                                  packet ->> 'traceparent' AS traceparent""")
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedPacket(
                        rs.getObject("packet_id", UUID.class),
                        rs.getString("packet"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getString("traceparent")))
                .list()
                .stream()
                .sorted(Comparator.comparing(ClaimedPacket::createdAt))
                .toList();
    }

    public void markSent(UUID packetId, Instant now) {
        jdbc.sql("UPDATE downlink_packets SET status = 'SENT', sent_at = :now, last_error = NULL "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("now", Timestamp.from(now)).param("id", packetId).update();
    }

    public void scheduleRetry(UUID packetId, Instant nextAttemptAt, String error) {
        jdbc.sql("UPDATE downlink_packets SET next_attempt_at = :next, last_error = :error "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("next", Timestamp.from(nextAttemptAt)).param("error", truncate(error)).param("id", packetId)
                .update();
    }

    public void markRejected(UUID packetId, String error) {
        jdbc.sql("UPDATE downlink_packets SET status = 'REJECTED', last_error = :error "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("error", truncate(error)).param("id", packetId).update();
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }

    /**
     * @param json     the stored packet exactly as it will be sent (and signed)
     * @param attempts including this one
     */
    /** @param traceparent the trace the report belongs to, read from the packet itself (null if none) */
    public record ClaimedPacket(UUID packetId, String json, int attempts, Instant createdAt, String traceparent) {
    }
}
