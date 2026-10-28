package ge.tsu.redlink.missioncontrol.uplink;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Uplink outbox bookkeeping in single SQL statements: claiming due packets with a lease and
 * recording delivery results. Same design as rover-sim's downlink (claim, send outside any
 * transaction, record), see SPEC §4.
 */
@Repository
@RequiredArgsConstructor
public class UplinkClaims {

    private final JdbcClient jdbc;

    /**
     * Reserves up to {@code limit} due packets until {@code leaseUntil}; concurrent dispatchers get
     * disjoint packets ({@code SKIP LOCKED}).
     *
     * @return claimed packets, oldest first
     */
    public List<ClaimedPacket> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE uplink_packets
                        SET next_attempt_at = :leaseUntil, attempts = attempts + 1
                        WHERE packet_id IN (
                            SELECT packet_id FROM uplink_packets
                            WHERE status = 'PENDING' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at, created_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING packet_id, command_id, packet, attempts, created_at,
                                  packet::jsonb ->> 'traceparent' AS traceparent""")
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedPacket(
                        rs.getObject("packet_id", UUID.class),
                        rs.getLong("command_id"),
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
        jdbc.sql("UPDATE uplink_packets SET status = 'SENT', sent_at = :now, last_error = NULL "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("now", Timestamp.from(now)).param("id", packetId).update();
    }

    public void scheduleRetry(UUID packetId, Instant nextAttemptAt, String error) {
        jdbc.sql("UPDATE uplink_packets SET next_attempt_at = :next, last_error = :error "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("next", Timestamp.from(nextAttemptAt)).param("error", truncate(error)).param("id", packetId)
                .update();
    }

    /** @return true if this call changed the packet (false if it was no longer pending) */
    public boolean markRejected(UUID packetId, String error) {
        return jdbc.sql("UPDATE uplink_packets SET status = 'REJECTED', last_error = :error "
                        + "WHERE packet_id = :id AND status = 'PENDING'")
                .param("error", truncate(error)).param("id", packetId).update() == 1;
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 500 ? error : error.substring(0, 500);
    }

    /** @param traceparent the trace of the command, read from the packet itself (null if untraced) */
    public record ClaimedPacket(UUID packetId, long commandId, String json, int attempts, Instant createdAt,
                                String traceparent) {
    }
}
