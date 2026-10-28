package ge.tsu.redlink.roversim.uplink;

import ge.tsu.redlink.contract.telemetry.CommandExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReceivedCommandRepository extends JpaRepository<ReceivedCommand, UUID> {

    /** The command a rover is executing right now, if any (there is never more than one). */
    Optional<ReceivedCommand> findFirstByRoverIdAndStatusOrderBySequenceNoAsc(long roverId, CommandExecutionStatus status);

    default Optional<ReceivedCommand> findExecuting(long roverId) {
        return findFirstByRoverIdAndStatusOrderBySequenceNoAsc(roverId, CommandExecutionStatus.EXECUTING);
    }

    /** The waiting command with the lowest sequence number. */
    default Optional<ReceivedCommand> findNextWaiting(long roverId) {
        return findFirstByRoverIdAndStatusOrderBySequenceNoAsc(roverId, CommandExecutionStatus.RECEIVED);
    }

    /**
     * Stores a newly received command unless its packet id or its (rover, sequence) slot is taken.
     * A single statement, so concurrent deliveries of one packet cannot both insert, and neither
     * fails with a constraint violation: one gets 1, the others 0.
     *
     * @return 1 if inserted, 0 if a row with the same packet id or sequence slot already exists
     */
    @Modifying
    @Query(value = """
            INSERT INTO received_commands
                (packet_id, rover_id, command_id, sequence_no, type, command, status, sent_at, received_at, trace_parent)
            VALUES
                (:packetId, :roverId, :commandId, :sequenceNo, :type, CAST(:command AS jsonb), 'RECEIVED', :sentAt, :receivedAt,
                 :traceParent)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("packetId") UUID packetId,
                       @Param("roverId") long roverId,
                       @Param("commandId") long commandId,
                       @Param("sequenceNo") long sequenceNo,
                       @Param("type") String type,
                       @Param("command") String commandJson,
                       @Param("sentAt") Instant sentAt,
                       @Param("receivedAt") Instant receivedAt,
                       @Param("traceParent") String traceParent);
}
