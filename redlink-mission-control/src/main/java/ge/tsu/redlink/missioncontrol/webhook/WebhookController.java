package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.CreateWebhookRequest;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.CreatedWebhookResponse;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.DeliveryResponse;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.WebhookResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Webhooks", description = "HTTP callbacks to your own systems when something happens")
@PreAuthorize("hasRole('VIEWER')")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhooks;

    @Operation(summary = "Register a webhook",
            description = "https URLs with public addresses only (SSRF protection). The secret is in this response "
                    + "only; deliveries carry X-RedLink-Signature: t=<epoch>,v1=<base64url HMAC-SHA256 of \"<t>.\" + body>.")
    @PostMapping
    ResponseEntity<CreatedWebhookResponse> register(@Valid @RequestBody CreateWebhookRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        CreatedWebhookResponse created = webhooks.register(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create(created.links().get("self").href()))
                .cacheControl(CacheControl.noStore()) // contains the secret
                .body(created);
    }

    @Operation(summary = "Your webhooks")
    @GetMapping
    List<WebhookResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return webhooks.list(jwt.getSubject());
    }

    @Operation(summary = "One of your webhooks")
    @GetMapping("/{id}")
    WebhookResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return webhooks.get(jwt.getSubject(), id);
    }

    @Operation(summary = "Delete a webhook", description = "Pending deliveries are dropped.")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        webhooks.delete(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send a test delivery (event ping)")
    @PostMapping("/{id}/ping")
    ResponseEntity<Void> ping(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        webhooks.ping(jwt.getSubject(), id);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Delivery log, newest first")
    @GetMapping("/{id}/deliveries")
    PagedModel<DeliveryResponse> deliveries(@PathVariable UUID id,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                            @AuthenticationPrincipal Jwt jwt) {
        return new PagedModel<>(webhooks.deliveries(jwt.getSubject(), id, PageRequest.of(page, size)));
    }
}
