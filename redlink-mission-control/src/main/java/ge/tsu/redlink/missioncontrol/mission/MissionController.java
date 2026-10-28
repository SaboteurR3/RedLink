package ge.tsu.redlink.missioncontrol.mission;

import ge.tsu.redlink.missioncontrol.command.CommandProperties;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService;
import ge.tsu.redlink.missioncontrol.ratelimit.SubmissionRateLimiter;
import ge.tsu.redlink.missioncontrol.idempotency.IdempotencyService.Outcome;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Missions", description = "Ordered plans of commands, checked as a whole before anything is queued")
@RequiredArgsConstructor
public class MissionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final MissionService missions;
    private final CommandProperties commandProperties;
    private final SubmissionRateLimiter rateLimiter;

    /**
     * {@code 202 Accepted}: the steps are queued, not done. Stored as text, so a replay is
     * byte-for-byte the original.
     */
    @Operation(summary = "Plan a mission (asynchronous, idempotent)",
            description = "The whole plan is simulated from Earth's last known state on the site's terrain first: "
                    + "422 mission-plan-infeasible names the first step the rover could not do; 409 rover-busy while "
                    + "the rover has unfinished commands. Requires Idempotency-Key.")
    @ApiResponse(responseCode = "202", description = "All steps queued (DRILL_SAMPLE steps wait for approval)",
            headers = {@Header(name = HttpHeaders.LOCATION, description = "The mission resource"),
                    @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until polling is worthwhile"),
                    @Header(name = IDEMPOTENT_REPLAYED, description = "true when this is a replayed response")},
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MissionResponse.class)))
    @PostMapping(path = "/rovers/{roverId}/missions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<String> submit(@PathVariable long roverId,
                                  @Parameter(description = "Unique per logical request, e.g. a UUID")
                                  @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                  @Valid @RequestBody MissionRequest request,
                                  @AuthenticationPrincipal Jwt jwt) {
        IdempotencyService.validateKey(idempotencyKey);
        rateLimiter.acquire(jwt.getSubject());
        Outcome outcome = missions.submit(roverId, request, jwt.getSubject(), idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.response().status())
                .location(URI.create(outcome.response().location()))
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(commandProperties.retryAfter().toSeconds()))
                .contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(outcome.response().body());
    }

    /**
     * A dry run: changes nothing, so it is safe to call as often as the user edits the plan. POST only
     * because the plan travels in the body; the answer is 200 (nothing was created) and never cached.
     */
    @Operation(summary = "Preview a command or plan (dry run)",
            description = "Simulates the steps from Earth's last known state on the terrain with the rover's specs: "
                    + "the path cell by cell, the end state and the first step that would fail. Sends nothing.")
    @PostMapping(path = "/rovers/{roverId}/plan-previews", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<PlanPreviewDtos.PlanPreviewResponse> preview(@PathVariable long roverId,
                                                                @Valid @RequestBody PlanPreviewDtos.PlanPreviewRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(missions.preview(roverId, request.steps()));
    }

    @Operation(summary = "Plan a drive to a cell",
            description = "The cheapest route around rocks and steep slopes from the last known position, as ROTATE/MOVE "
                    + "commands with their preview. Sends nothing; submit the steps as a mission. 422 no-route if unreachable.")
    @PostMapping(path = "/rovers/{roverId}/routes", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<PlanPreviewDtos.RouteResponse> route(@PathVariable long roverId,
                                                        @Valid @RequestBody PlanPreviewDtos.RouteRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(missions.route(roverId, request.x(), request.y()));
    }

    @Operation(summary = "Get a mission", description = "Status and progress are derived from the steps. "
            + "The ETag changes whenever any step changes; supports If-None-Match.")
    @GetMapping("/missions/{missionId}")
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<MissionResponse> get(@PathVariable long missionId) {
        return respond(missions.get(missionId));
    }

    @Operation(summary = "List a rover's missions, newest first")
    @GetMapping("/rovers/{roverId}/missions")
    @PreAuthorize("hasRole('VIEWER')")
    PagedModel<MissionResponse> list(@PathVariable long roverId,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return new PagedModel<>(missions.list(roverId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("id")))));
    }

    @Operation(summary = "Cancel a mission",
            description = "Withdraws every step still on Earth. 200 (also when repeated); 409 when all steps have left Earth.")
    @PostMapping("/missions/{missionId}/cancel")
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<MissionResponse> cancel(@PathVariable long missionId, @AuthenticationPrincipal Jwt jwt) {
        return respond(missions.cancel(missionId, jwt.getSubject()));
    }

    private static ResponseEntity<MissionResponse> respond(Tagged<MissionResponse> mission) {
        return ResponseEntity.ok().eTag(mission.etag()).cacheControl(REVALIDATE).body(mission.body());
    }
}
