package ge.tsu.redlink.missioncontrol.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A registered HTTP callback. Implements {@link Persistable} because the id is generated here
 * (a random UUID), so Spring Data cannot tell new from existing by the id.
 */
@Entity
@Table(name = "webhooks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class Webhook implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64, updatable = false)
    private String owner;

    @Column(nullable = false, length = 2048, updatable = false)
    private String url;

    @Column(nullable = false, length = 128, updatable = false)
    private String secret;

    /** Wire names of {@link WebhookEvent}s. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] events;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    Webhook(String owner, String url, String secret, List<WebhookEvent> events, Instant now) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.url = url;
        this.secret = secret;
        this.events = events.stream().map(WebhookEvent::wireName).distinct().sorted().toArray(String[]::new);
        this.active = true;
        this.createdAt = now;
        this.isNew = true;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    /** The receiver said "410 Gone": stop sending. */
    void deactivate() {
        this.active = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    String getSecret() {
        return secret;
    }

    public List<String> getEvents() {
        return List.of(events);
    }

}
