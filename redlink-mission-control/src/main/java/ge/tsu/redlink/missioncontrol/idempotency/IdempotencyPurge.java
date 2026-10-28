package ge.tsu.redlink.missioncontrol.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired idempotency keys every hour so the table does not grow forever.
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
class IdempotencyPurge {

    private final IdempotencyService idempotency;

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    void purge() {
        int deleted = idempotency.purgeExpired();
        if (deleted > 0) {
            log.info("Purged {} expired idempotency keys", deleted);
        }
    }
}
