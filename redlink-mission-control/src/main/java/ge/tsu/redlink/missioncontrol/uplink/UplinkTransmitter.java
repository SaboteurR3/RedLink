package ge.tsu.redlink.missioncontrol.uplink;

import ge.tsu.redlink.contract.command.CommandPacket;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.missioncontrol.command.Command;
import ge.tsu.redlink.missioncontrol.command.CommandRepository;
import ge.tsu.redlink.missioncontrol.command.CommandResponse;
import ge.tsu.redlink.missioncontrol.dsn.DsnService;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.events.UplinkTransmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Moves queued commands onto the uplink: sequence number, TRANSMITTED, outbox packet — all in one
 * transaction, so a command is either fully on its way or still cancellable, never in between.
 */
@Service
@EnableConfigurationProperties(UplinkProperties.class)
@Slf4j
@RequiredArgsConstructor
public class UplinkTransmitter {

    /** Arbitrary but fixed key of the PostgreSQL advisory lock that makes transmission exclusive. */
    private static final long TRANSMITTER_LOCK = 0x5265644C696E6BL; // "RedLink"

    private final CommandRepository commands;
    private final JdbcClient jdbc;
    private final RoverEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final UplinkProperties properties;
    private final DsnService dsn;

    /**
     * Transmits queued commands whose hold time is over, oldest first.
     *
     * @return number of commands transmitted
     */
    @Transactional
    public int transmitDue() {
        // Only one transmitter at a time, cluster-wide. Two running side by side would each take
        // a different batch and draw sequence numbers interleaved, so a newer command could get a
        // lower number and run first on the rover. The lock ends with the transaction.
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(?)").param(TRANSMITTER_LOCK).query(Boolean.class).single()) {
            return 0;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // No relay window, or the Sun in the way: everything waits on Earth, still cancellable.
        if (!dsn.uplinkOpen(now)) {
            return 0;
        }
        // Row locks for the rest of the transaction: a concurrent cancel waits and then fails its
        // version check, so a command is never both cancelled and transmitted.
        // A mission step waits until every earlier step of its mission has left Earth (or ended), so
        // a step held for approval holds back the rest and the plan never runs with a hole in it.
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM commands c
                        WHERE c.status = 'QUEUED' AND c.queued_at <= ?
                          AND NOT EXISTS (SELECT 1 FROM commands earlier
                                          WHERE earlier.mission_id = c.mission_id
                                            AND earlier.mission_step < c.mission_step
                                            AND earlier.status IN ('PENDING_APPROVAL', 'QUEUED'))
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED""")
                .params(Timestamp.from(now.minus(properties.queueHold())), properties.batchSize())
                .query(Long.class).list();

        for (Long id : ids) {
            Command command = commands.findById(id).orElseThrow();
            long sequenceNo = nextSequence(command.getRoverId());
            command.transmit(sequenceNo, now);

            CommandPacket packet = new CommandPacket(UUID.randomUUID(), command.getRoverId(), command.getId(), sequenceNo,
                    jsonMapper.readValue(command.getCommand(), RoverCommand.class), now);
            Instant arrival = now.plus(properties.oneWayDelay());
            jdbc.sql("""
                            INSERT INTO uplink_packets (packet_id, command_id, rover_id, packet, created_at, deliver_at, next_attempt_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""")
                    .params(packet.packetId(), command.getId(), command.getRoverId(), jsonMapper.writeValueAsString(packet),
                            Timestamp.from(now), Timestamp.from(arrival), Timestamp.from(arrival))
                    .update();
            log.info("Transmitted command {} to rover {} as sequence {}; reaches Mars at {}",
                    command.getId(), command.getRoverId(), sequenceNo, arrival);
            events.publish(command.getRoverId(), EventType.COMMAND, CommandResponse.of(command, packet.command()));
            events.publish(command.getRoverId(), EventType.UPLINK,
                    new UplinkTransmission(command.getId(), sequenceNo, now, arrival));
        }
        commands.flush();
        return ids.size();
    }

    /** Atomic per-rover counter: concurrent callers serialize on the counter row. */
    private long nextSequence(long roverId) {
        return jdbc.sql("UPDATE uplink_sequences SET last_sequence_no = last_sequence_no + 1 WHERE rover_id = ? "
                        + "RETURNING last_sequence_no")
                .param(roverId).query(Long.class).single();
    }
}
