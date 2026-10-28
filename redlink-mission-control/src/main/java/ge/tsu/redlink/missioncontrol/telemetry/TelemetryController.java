package ge.tsu.redlink.missioncontrol.telemetry;

import ge.tsu.redlink.contract.telemetry.TelemetryType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/rovers/{roverId}/telemetry")
@Tag(name = "Telemetry", description = "What the rover reported, newest first")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetry;

    @Operation(summary = "Telemetry history of a rover", description = "Newest first by Mars time; [from, to) range.")
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    PagedModel<TelemetryResponse> history(
            @PathVariable long roverId,
            @RequestParam(required = false) TelemetryType type,
            @Parameter(description = "Inclusive, ISO-8601 instant (Mars time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Exclusive, ISO-8601 instant (Mars time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        Sort newestFirst = Sort.by(Sort.Order.desc("roverTime"), Sort.Order.desc("id"));
        return new PagedModel<>(telemetry.history(roverId, type, from, to, PageRequest.of(page, size, newestFirst)));
    }
}
