package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher.RoverEventRecorded;
import ge.tsu.redlink.missioncontrol.rover.RoverService;
import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Open SSE streams, per rover. A new stream first gets either a snapshot of the rover or the events
 * it missed (resume), then every new event once its transaction has committed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SseHub {

    private static final long RECONNECT_MILLIS = 3_000;
    /**
     * Live catch-up after a notification only covers what was just committed elsewhere; a resuming
     * client can be much further behind, which {@code replayLimit} caps.
     */
    private static final int CATCH_UP_LIMIT = 1_000;

    private final Map<Long, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final JdbcClient jdbc;
    private final RoverService rovers;
    private final JsonMapper jsonMapper;
    private final EventProperties properties;
    private final Clock clock;

    /**
     * @param lastEventId the last event the client saw, or null for a fresh start (snapshot)
     * @throws TooManySubscribersException when this instance serves the maximum number of streams
     */
    public SseEmitter subscribe(long roverId, Long lastEventId) {
        if (open.incrementAndGet() > properties.maxSubscribers()) {
            open.decrementAndGet();
            throw new TooManySubscribersException();
        }
        SseEmitter emitter = new SseEmitter(properties.emitterTimeout().toMillis());
        Subscription subscription = new Subscription(roverId, emitter);
        emitter.onCompletion(() -> unsubscribe(subscription));
        emitter.onTimeout(() -> unsubscribe(subscription));
        emitter.onError(error -> unsubscribe(subscription));

        // Registered before the catch-up, under the subscription's lock: live events that commit
        // meanwhile wait, and are then sent only if newer than what the catch-up sent.
        synchronized (subscription) {
            subscribers.computeIfAbsent(roverId, id -> ConcurrentHashMap.newKeySet()).add(subscription);
            try {
                if (lastEventId == null) {
                    sendSnapshot(subscription, EventType.ROVER);
                } else {
                    replay(subscription, lastEventId, properties.replayLimit());
                }
            } catch (IOException e) {
                unsubscribe(subscription);
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    /** Delivers a committed event to the rover's open streams. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRecorded(RoverEventRecorded event) {
        for (Subscription subscription : subscribers.getOrDefault(event.roverId(), Set.of())) {
            try {
                subscription.send(event.id(), event.type(), event.json());
            } catch (IOException | IllegalStateException e) {
                // The client went away; the emitter callbacks may not have fired yet.
                unsubscribe(subscription);
            }
        }
    }

    /**
     * Sends the rover's streams every stored event they have not seen yet, in order; events
     * committed by another instance reach this instance's streams this way. Streams already
     * up to date receive nothing (the same event is never sent twice).
     */
    void catchUp(long roverId) {
        for (Subscription subscription : subscribers.getOrDefault(roverId, Set.of())) {
            synchronized (subscription) {
                try {
                    replay(subscription, subscription.lastSentId(), CATCH_UP_LIMIT);
                } catch (IOException | IllegalStateException e) {
                    unsubscribe(subscription);
                }
            }
        }
    }

    void catchUpAll() {
        subscribers.keySet().forEach(this::catchUp);
    }

    /** A comment line, so proxies and load balancers do not close idle streams. */
    void heartbeat() {
        subscribers.values().forEach(set -> set.forEach(subscription -> {
            try {
                subscription.comment("keepalive");
            } catch (IOException | IllegalStateException e) {
                unsubscribe(subscription);
            }
        }));
    }

    int purgeOldEvents() {
        return jdbc.sql("DELETE FROM rover_events WHERE created_at < ?")
                .param(Timestamp.from(clock.instant().minus(properties.retention()))).update();
    }

    int openStreams() {
        return open.get();
    }

    private void replay(Subscription subscription, long lastEventId, int limit) throws IOException {
        List<StoredEvent> missed = jdbc.sql("""
                        SELECT id, type, data FROM rover_events WHERE rover_id = ? AND id > ? ORDER BY id LIMIT ?""")
                .params(subscription.roverId(), lastEventId, limit + 1)
                .query((rs, row) -> new StoredEvent(rs.getLong("id"), EventType.valueOf(rs.getString("type")), rs.getString("data")))
                .list();
        if (missed.size() > limit) {
            sendSnapshot(subscription, EventType.RESET); // too far behind: start over
            return;
        }
        for (StoredEvent event : missed) {
            subscription.send(event.id(), event.type(), event.data());
        }
    }

    /**
     * The current rover, carrying the newest event id so a later resume continues from here. The id
     * is read before the rover: an event committing in between is then sent again live (harmless),
     * never lost.
     */
    private void sendSnapshot(Subscription subscription, EventType type) throws IOException {
        long latestId = jdbc.sql("SELECT coalesce(max(id), 0) FROM rover_events WHERE rover_id = ?")
                .param(subscription.roverId()).query(Long.class).single();
        String rover = jsonMapper.writeValueAsString(rovers.get(subscription.roverId()).body());
        subscription.send(latestId, type, rover);
    }

    private void unsubscribe(Subscription subscription) {
        Set<Subscription> set = subscribers.get(subscription.roverId());
        if (set != null && set.remove(subscription)) {
            open.decrementAndGet();
            log.debug("Stream of rover {} closed", subscription.roverId());
        }
    }

    private record StoredEvent(long id, EventType type, String data) {
    }

    /** One open stream. All sends go through its monitor, so events never interleave or reorder. */
    private static final class Subscription {

        private final long roverId;
        private final SseEmitter emitter;
        private long lastSentId = -1;
        private boolean first = true;

        Subscription(long roverId, SseEmitter emitter) {
            this.roverId = roverId;
            this.emitter = emitter;
        }

        long roverId() {
            return roverId;
        }

        synchronized long lastSentId() {
            return lastSentId;
        }

        synchronized void send(long id, EventType type, String json) throws IOException {
            if (id <= lastSentId) {
                return; // already covered by the snapshot or the catch-up
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event().id(String.valueOf(id)).name(type.wireName()).data(json);
            if (first) {
                event.reconnectTime(RECONNECT_MILLIS);
                first = false;
            }
            emitter.send(event);
            lastSentId = Math.max(lastSentId, id);
        }

        synchronized void comment(String text) throws IOException {
            emitter.send(SseEmitter.event().comment(text));
        }
    }

    /** 503: this instance serves as many streams as it may. */
    public static class TooManySubscribersException extends ApiException {

        public TooManySubscribersException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.TOO_MANY_SUBSCRIBERS, "Too many streams",
                    "The event stream is at capacity; retry shortly");
        }

        @Override
        public Map<String, String> headers() {
            return Map.of(HttpHeaders.RETRY_AFTER, "10");
        }
    }
}
