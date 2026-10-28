package ge.tsu.redlink.missioncontrol.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stream heartbeats, and hourly purging of events older than the retention.
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
class EventMaintenance implements SchedulingConfigurer {

    private final SseHub hub;
    private final EventProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(hub::heartbeat, properties.heartbeat());
        registrar.addFixedDelayTask(this::purge, Duration.ofHours(1));
    }

    private void purge() {
        int deleted = hub.purgeOldEvents();
        if (deleted > 0) {
            log.info("Purged {} stream events older than {}", deleted, properties.retention());
        }
    }
}
