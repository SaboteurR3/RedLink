package ge.tsu.redlink.missioncontrol.audit;

import java.time.Instant;

public record AuditEntryResponse(long id, Instant occurredAt, String actor, AuditAction action, String target,
                                 String detail) {

    static AuditEntryResponse of(AuditEntry entry) {
        return new AuditEntryResponse(entry.getId(), entry.getOccurredAt(), entry.getActor(), entry.getAction(),
                entry.getTarget(), entry.getDetail());
    }
}
