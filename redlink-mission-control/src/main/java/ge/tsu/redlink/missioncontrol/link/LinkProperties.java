package ge.tsu.redlink.missioncontrol.link;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code redlink.link.*}: the deep-space link between mission-control and rover-sim.
 *
 * @param secret             base64 HMAC key shared with rover-sim, at least 32 bytes
 * @param signatureTolerance how far a packet's signature timestamp may be from now
 * @param maxPacketSize      larger packets are refused before their signature is checked
 */
@Validated
@ConfigurationProperties("redlink.link")
public record LinkProperties(
        @NotBlank String secret,
        @NotNull Duration signatureTolerance,
        @NotNull DataSize maxPacketSize) {
}
