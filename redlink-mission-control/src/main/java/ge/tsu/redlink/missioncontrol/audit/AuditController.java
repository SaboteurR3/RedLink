package ge.tsu.redlink.missioncontrol.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Who changed what and when (flight directors only)")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLog audit;

    @Operation(summary = "The audit log", description = "Newest first; [from, to) range. Paged, with RFC 8288 Link headers.")
    @GetMapping
    @PreAuthorize("hasRole('FLIGHT_DIRECTOR')")
    PagedModel<AuditEntryResponse> search(
            @RequestParam(required = false) @Size(max = 64) String actor,
            @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Inclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Exclusive, ISO-8601 instant")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Sort newestFirst = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));
        return new PagedModel<>(audit.search(actor, action, from, to, PageRequest.of(page, size, newestFirst)));
    }
}
