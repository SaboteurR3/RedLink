package ge.tsu.redlink.missioncontrol.audit;

import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Who changed what and when. Services record inside their own transaction, so an entry exists
 * exactly when the change was committed: a rolled-back change leaves no trace, and a committed
 * one always has its line.
 */
@Service
@RequiredArgsConstructor
public class AuditLog {

    private static final int MAX_DETAIL = 500;

    private final AuditRepository entries;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, AuditAction action, String target, String detail) {
        String shortened = detail == null || detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL - 1) + "…";
        entries.save(new AuditEntry(clock.instant().truncatedTo(ChronoUnit.MICROS), actor, action, target, shortened));
    }

    /** Newest first; every filter is optional. */
    @Transactional(readOnly = true)
    public Page<AuditEntryResponse> search(String actor, AuditAction action, Instant from, Instant to, Pageable pageable) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidRequestException("The time range is empty", List.of("from: must be before to"));
        }
        Specification<AuditEntry> filter = Specification.unrestricted();
        if (actor != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("actor"), actor));
        }
        if (action != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (from != null) {
            filter = filter.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        }
        if (to != null) {
            filter = filter.and((root, query, cb) -> cb.lessThan(root.get("occurredAt"), to));
        }
        return entries.findAll(filter, pageable).map(AuditEntryResponse::of);
    }
}
