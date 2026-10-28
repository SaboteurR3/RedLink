package ge.tsu.redlink.missioncontrol.rover;

import ge.tsu.redlink.missioncontrol.web.Tagged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/rovers")
@Tag(name = "Rovers", description = "Rovers and their last known state")
@RequiredArgsConstructor
public class RoverController {

    static final String MERGE_PATCH_JSON = "application/merge-patch+json";

    /**
     * Clients may keep a copy but must revalidate it (with If-None-Match) before every use;
     * private because the response depends on who is logged in.
     */
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final RoverService rovers;

    @Operation(summary = "List rovers")
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    PagedModel<RoverResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // Sorting is fixed: arbitrary client-chosen sort properties would expose the entity's internals.
        return new PagedModel<>(rovers.list(PageRequest.of(page, size, Sort.by("id"))));
    }

    /**
     * Spring answers {@code 304 Not Modified} (no body) when {@code If-None-Match} matches the
     * {@code ETag} set here.
     */
    @Operation(summary = "Get a rover", description = "Supports If-None-Match → 304 Not Modified.")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<RoverResponse> get(@PathVariable long id) {
        return respond(rovers.get(id));
    }

    @Operation(summary = "Rename a rover (JSON Merge Patch)",
            description = "Requires If-Match with the current ETag: missing → 428, stale → 412.")
    @PatchMapping(path = "/{id}", consumes = MERGE_PATCH_JSON)
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<RoverResponse> patch(@PathVariable long id,
                                        @Parameter(description = "Current ETag of the rover, or *")
                                        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                        @RequestBody JsonNode mergePatch) {
        return respond(rovers.patch(id, ifMatch, RoverPatch.from(mergePatch)));
    }

    private static ResponseEntity<RoverResponse> respond(Tagged<RoverResponse> rover) {
        return ResponseEntity.ok().eTag(rover.etag()).cacheControl(REVALIDATE).body(rover.body());
    }
}
