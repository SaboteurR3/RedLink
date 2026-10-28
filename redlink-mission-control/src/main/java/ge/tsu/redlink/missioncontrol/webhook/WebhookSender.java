package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDeliveries.ClaimedDelivery;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Sends one webhook delivery. The URL is checked against the SSRF policy again right before the
 * call, because the host's DNS may have changed since registration.
 */
@Component
class WebhookSender {

    static final String SIGNATURE_HEADER = "X-RedLink-Signature";
    static final String EVENT_HEADER = "X-RedLink-Event";
    static final String DELIVERY_HEADER = "X-RedLink-Delivery";

    private final HttpClient httpClient;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;
    private final Clock clock;

    WebhookSender(WebhookUrlPolicy urlPolicy, WebhookProperties properties, Clock clock) {
        this.httpClient = HttpClient.newBuilder()
                // A redirect could point anywhere, including into our own network: never follow.
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        this.urlPolicy = urlPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    Result send(ClaimedDelivery delivery) {
        List<String> violations = urlPolicy.violations(delivery.url());
        if (!violations.isEmpty()) {
            return new Result(Outcome.RETRY, null, "URL refused: " + String.join("; ", violations));
        }
        byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
        // Same signature scheme as the link packets, keyed with this webhook's own secret.
        PacketSigner signer = new PacketSigner(delivery.secret().getBytes(StandardCharsets.UTF_8), clock, Duration.ofMinutes(5));
        HttpRequest request = HttpRequest.newBuilder(URI.create(delivery.url()))
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", "RedLink-Webhooks/1")
                .header(SIGNATURE_HEADER, signer.sign(body))
                .header(EVENT_HEADER, delivery.event())
                .header(DELIVERY_HEADER, delivery.id().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return new Result(Outcome.DELIVERED, status, null);
            }
            if (status == 410) {
                return new Result(Outcome.GONE, status, "HTTP 410 Gone: the receiver asked us to stop");
            }
            return new Result(Outcome.RETRY, status, "HTTP " + status);
        } catch (IOException | IllegalArgumentException e) {
            return new Result(Outcome.RETRY, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.RETRY, null, "interrupted");
        }
    }

    enum Outcome {
        DELIVERED,
        /** 410 Gone: the receiver does not want any more deliveries. */
        GONE,
        RETRY
    }

    record Result(Outcome outcome, Integer statusCode, String error) {
    }
}
