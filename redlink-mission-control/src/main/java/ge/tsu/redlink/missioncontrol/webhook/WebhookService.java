package ge.tsu.redlink.missioncontrol.webhook;

import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.UnprocessableException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.CreateWebhookRequest;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.CreatedWebhookResponse;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.DeliveryResponse;
import ge.tsu.redlink.missioncontrol.webhook.WebhookDtos.WebhookResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Registering and managing webhooks. Every webhook belongs to its creator; looking up someone
 * else's gives "not found", so ids cannot even be probed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookRepository webhooks;
    private final WebhookNotifier notifier;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * @throws InvalidRequestException if the URL is not allowed (SSRF rules) or an event is unknown
     * @throws UnprocessableException  if the user already has the maximum number of webhooks
     */
    @Transactional
    public CreatedWebhookResponse register(String owner, CreateWebhookRequest request) {
        List<String> problems = new ArrayList<>(urlPolicy.violations(request.url()));
        List<WebhookEvent> events = new ArrayList<>();
        for (String name : request.events()) {
            Optional<WebhookEvent> event = WebhookEvent.fromWireName(name);
            if (event.isEmpty() || event.get() == WebhookEvent.PING) {
                problems.add("events: '" + name + "' is not one of command.completed, command.failed, "
                        + "rover.safe_mode, rover.battery_low");
            } else {
                events.add(event.get());
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException("The webhook cannot be registered", problems);
        }
        if (webhooks.countByOwner(owner) >= properties.maxPerUser()) {
            throw new UnprocessableException(ProblemTypes.WEBHOOK_LIMIT, "Too many webhooks",
                    "You already have " + properties.maxPerUser() + " webhooks; delete one first");
        }
        Webhook webhook = webhooks.save(new Webhook(owner, request.url(), newSecret(), events,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        log.info("{} registered webhook {} for {}", owner, webhook.getId(), webhook.getEvents());
        return CreatedWebhookResponse.of(webhook);
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list(String owner) {
        return webhooks.findByOwnerOrderByCreatedAtAsc(owner).stream().map(WebhookResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public WebhookResponse get(String owner, UUID id) {
        return WebhookResponse.of(find(owner, id));
    }

    /** Deliveries still waiting are deleted with it (ON DELETE CASCADE). */
    @Transactional
    public void delete(String owner, UUID id) {
        webhooks.delete(find(owner, id));
        log.info("{} deleted webhook {}", owner, id);
    }

    /** Queues a test delivery. */
    @Transactional
    public void ping(String owner, UUID id) {
        Webhook webhook = find(owner, id);
        notifier.ping(webhook.getId(), Map.of("message", "Hello from RedLink mission control", "webhookId", webhook.getId()));
    }

    @Transactional(readOnly = true)
    public Page<DeliveryResponse> deliveries(String owner, UUID id, Pageable pageable) {
        find(owner, id);
        List<DeliveryResponse> content = jdbc.sql("""
                        SELECT id, event, status, attempts, created_at, delivered_at, next_attempt_at, last_status_code, last_error
                        FROM webhook_deliveries WHERE webhook_id = ?
                        ORDER BY created_at DESC, id
                        LIMIT ? OFFSET ?""")
                .params(id, pageable.getPageSize(), pageable.getOffset())
                .query((rs, row) -> new DeliveryResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("event"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("delivered_at")),
                        "PENDING".equals(rs.getString("status")) ? instant(rs.getTimestamp("next_attempt_at")) : null,
                        (Integer) rs.getObject("last_status_code"),
                        rs.getString("last_error")))
                .list();
        long total = jdbc.sql("SELECT count(*) FROM webhook_deliveries WHERE webhook_id = ?").param(id)
                .query(Long.class).single();
        return new PageImpl<>(content, pageable, total);
    }

    private Webhook find(String owner, UUID id) {
        return webhooks.findByIdAndOwner(id, owner).orElseThrow(() ->
                new NotFoundException(ProblemTypes.WEBHOOK_NOT_FOUND, "You have no webhook " + id));
    }

    /** 256 random bits; the prefix makes a leaked secret recognisable (e.g. by secret scanners). */
    private String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
