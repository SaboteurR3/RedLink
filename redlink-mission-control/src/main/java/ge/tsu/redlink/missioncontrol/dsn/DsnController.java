package ge.tsu.redlink.missioncontrol.dsn;

import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.ConjunctionRequest;
import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.ConjunctionResponse;
import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.LinkStatus;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/link")
@Tag(name = "Link", description = "The deep-space link: light time, relay windows, solar conjunction")
@RequiredArgsConstructor
public class DsnController {

    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final DsnService dsn;

    /** Depends on the clock (windows open and close), so it has no entity tag; clients revalidate. */
    @Operation(summary = "Link status", description = "Light time, relay window (open now? next change?), conjunction.")
    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<LinkStatus> status() {
        return ResponseEntity.ok().cacheControl(REVALIDATE).body(dsn.status());
    }

    @Operation(summary = "Solar conjunction state", description = "Supports If-None-Match → 304.")
    @GetMapping("/conjunction")
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<ConjunctionResponse> conjunction() {
        return respond(dsn.conjunction());
    }

    @Operation(summary = "Start or end a solar conjunction",
            description = "Idempotent: the same value again is 200 and changes nothing. While active, commands stay on "
                    + "Earth and the downlink answers 503 with Retry-After. Optional If-Match → 412 when stale.")
    @PutMapping("/conjunction")
    @PreAuthorize("hasRole('FLIGHT_DIRECTOR')")
    ResponseEntity<ConjunctionResponse> setConjunction(
            @Parameter(description = "ETag of the conjunction state, or *")
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody ConjunctionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return respond(dsn.setConjunction(request.active(), jwt.getSubject(), ifMatch));
    }

    private static ResponseEntity<ConjunctionResponse> respond(Tagged<ConjunctionResponse> conjunction) {
        return ResponseEntity.ok().eTag(conjunction.etag()).cacheControl(REVALIDATE).body(conjunction.body());
    }
}
