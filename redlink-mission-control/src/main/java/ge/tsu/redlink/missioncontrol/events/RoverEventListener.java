package ge.tsu.redlink.missioncontrol.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.OptionalLong;

/**
 * Makes live updates work with several mission-control instances (ticket 34). A browser's stream
 * is open on one instance, but the change it should see may commit on another: a telemetry packet
 * lands wherever the load balancer sends it. Every instance LISTENs on the channel
 * {@link RoverEventPublisher#CHANNEL}; a notification makes the {@link SseHub} send that rover's
 * streams whatever they have not seen yet, read from {@code rover_events} in order.
 *
 * <p>One connection is held for the listen (PostgreSQL delivers notifications to the session that
 * listens). If it breaks, the listener reconnects and then catches every stream up, so nothing
 * committed meanwhile is lost. Off with {@code redlink.events.cross-instance=false}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "redlink.events.cross-instance", havingValue = "true", matchIfMissing = true)
public class RoverEventListener implements SmartLifecycle {

    private static final int POLL_MILLIS = 500;
    private static final long RECONNECT_MILLIS = 2_000;

    private final DataSource dataSource;
    private final SseHub hub;
    private volatile boolean running;
    private Thread thread;

    @Override
    public void start() {
        running = true;
        thread = new Thread(this::run, "rover-events-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                listen(connection);
            } catch (SQLException | RuntimeException e) {
                if (!running) {
                    return;
                }
                log.warn("Listening for rover events failed ({}); reconnecting", e.getMessage());
                try {
                    Thread.sleep(RECONNECT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void listen(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + RoverEventPublisher.CHANNEL);
        }
        PGConnection postgres = connection.unwrap(PGConnection.class);
        log.info("Listening for rover events of other instances");
        hub.catchUpAll(); // anything committed while this listener was not connected
        while (running) {
            PGNotification[] notifications = postgres.getNotifications(POLL_MILLIS);
            if (notifications == null) {
                continue;
            }
            for (PGNotification notification : notifications) {
                roverIdOf(notification.getParameter()).ifPresent(hub::catchUp);
            }
        }
    }

    /** {@code "<roverId>:<eventId>"}; anything else on the channel is ignored. */
    static OptionalLong roverIdOf(String payload) {
        int colon = payload == null ? -1 : payload.indexOf(':');
        try {
            return colon > 0 ? OptionalLong.of(Long.parseLong(payload.substring(0, colon))) : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
