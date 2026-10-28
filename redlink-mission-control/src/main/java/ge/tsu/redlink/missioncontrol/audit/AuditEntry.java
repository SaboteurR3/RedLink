package ge.tsu.redlink.missioncontrol.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** One line of the audit log: written once, never changed. */
@Entity
@Table(name = "audit_log")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    private String actor;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    private String target;

    private String detail;

    AuditEntry(Instant occurredAt, String actor, AuditAction action, String target, String detail) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }
}
