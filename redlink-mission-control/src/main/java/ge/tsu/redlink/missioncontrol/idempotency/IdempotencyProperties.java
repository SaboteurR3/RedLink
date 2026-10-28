package ge.tsu.redlink.missioncontrol.idempotency;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.idempotency.*}.
 *
 * @param retention how long a key is remembered; afterwards the same key starts a new request
 */
@Validated
@ConfigurationProperties("redlink.idempotency")
public record IdempotencyProperties(@NotNull Duration retention) {
}
