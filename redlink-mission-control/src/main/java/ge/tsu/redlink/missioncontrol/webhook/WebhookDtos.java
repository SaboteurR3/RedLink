package ge.tsu.redlink.missioncontrol.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request and response bodies of the webhooks API.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class WebhookDtos {

    /**
     * @param events wire names, e.g. {@code command.completed}
     */
    public record CreateWebhookRequest(
            @NotNull String url,
            @NotNull @Size(min = 1, max = 10) List<@NotNull String> events) {
    }

    public record WebhookResponse(
            UUID id,
            String url,
            List<String> events,
            boolean active,
            Instant createdAt,
            @JsonProperty("_links") Map<String, Link> links) {

        static WebhookResponse of(Webhook webhook) {
            String self = "/api/v1/webhooks/" + webhook.getId();
            return new WebhookResponse(webhook.getId(), webhook.getUrl(), webhook.getEvents(), webhook.isActive(),
                    webhook.getCreatedAt(), Map.of(
                    "self", new Link(self, null),
                    "deliveries", new Link(self + "/deliveries", null),
                    "ping", new Link(self + "/ping", "POST")));
        }
    }

    /**
     * Returned once, at creation: the only time the secret is shown.
     *
     * @param secret verify {@code X-RedLink-Signature} with it: HMAC-SHA256 over {@code "<t>." + body}
     */
    public record CreatedWebhookResponse(
            UUID id,
            String url,
            List<String> events,
            boolean active,
            Instant createdAt,
            String secret,
            @JsonProperty("_links") Map<String, Link> links) {

        static CreatedWebhookResponse of(Webhook webhook) {
            WebhookResponse view = WebhookResponse.of(webhook);
            return new CreatedWebhookResponse(view.id(), view.url(), view.events(), view.active(), view.createdAt(),
                    webhook.getSecret(), view.links());
        }

        @Override
        public String toString() {
            return "CreatedWebhookResponse[id=" + id + ", secret=***]";
        }
    }

    /**
     * One attempted notification.
     *
     * @param lastStatusCode HTTP status of the last attempt, null if no answer came
     */
    public record DeliveryResponse(
            UUID id,
            String event,
            String status,
            int attempts,
            Instant createdAt,
            Instant deliveredAt,
            Instant nextAttemptAt,
            Integer lastStatusCode,
            String lastError) {
    }

    public record Link(String href, String method) {
    }
}
