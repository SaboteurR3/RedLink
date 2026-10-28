package ge.tsu.redlink.roversim.downlink;

import ge.tsu.redlink.contract.telemetry.TelemetryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.util.UUID;

/**
 * A telemetry packet waiting in the outbox for its delivery time.
 *
 * <p>Implements {@link Persistable} because the id is assigned by us (the packet id), so Spring
 * Data cannot tell a new row from an existing one; without this, {@code save} would issue a
 * needless SELECT before every INSERT.
 */
@Entity
@Table(name = "downlink_packets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs it; nobody else may create an empty one
public class DownlinkPacket implements Persistable<UUID> {

    public enum Status {
        PENDING,
        SENT,
        /** Earth refused the packet itself (4xx that a retry cannot fix). Kept, never resent. */
        REJECTED
    }

    @Id
    @Column(name = "packet_id")
    private UUID packetId;

    @Column(name = "rover_id", nullable = false)
    private long roverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TelemetryType type;

    /** The complete {@link ge.tsu.redlink.contract.telemetry.TelemetryPacket} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String packet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deliver_at", nullable = false)
    private Instant deliverAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Transient
    private boolean isNew;

    DownlinkPacket(UUID packetId, long roverId, TelemetryType type, String packetJson,
                   Instant createdAt, Instant deliverAt) {
        this.packetId = packetId;
        this.roverId = roverId;
        this.type = type;
        this.packet = packetJson;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
        this.deliverAt = deliverAt;
        this.attempts = 0;
        this.nextAttemptAt = deliverAt;
        this.isNew = true;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return packetId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

}
