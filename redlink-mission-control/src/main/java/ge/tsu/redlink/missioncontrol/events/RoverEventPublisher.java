package ge.tsu.redlink.missioncontrol.events;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * Records a stream event as part of the caller's transaction. The row makes it replayable
 * (Last-Event-ID); the in-memory notification is delivered to open streams only after the
 * transaction commits, so a change that rolls back is never streamed.
 */
@Service
@RequiredArgsConstructor
public class RoverEventPublisher {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEvents;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(long roverId, EventType type, Object data) {
        String json = jsonMapper.writeValueAsString(data);
        long id = jdbc.sql("INSERT INTO rover_events (rover_id, type, data, created_at) VALUES (?, ?, ?, ?) RETURNING id")
                .params(roverId, type.name(), json, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
                .query(Long.class).single();
        applicationEvents.publishEvent(new RoverEventRecorded(id, roverId, type, json));
    }

    /** In-memory notification; {@link SseHub} receives it after commit. */
    record RoverEventRecorded(long id, long roverId, EventType type, String json) {
    }
}
