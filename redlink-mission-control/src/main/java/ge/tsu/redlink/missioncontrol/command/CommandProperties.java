package ge.tsu.redlink.missioncontrol.command;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.commands.*}.
 *
 * @param retryAfter sent as {@code Retry-After} with 202: when polling the command is first worthwhile
 */
@Validated
@ConfigurationProperties("redlink.commands")
public record CommandProperties(@NotNull Duration retryAfter) {
}
