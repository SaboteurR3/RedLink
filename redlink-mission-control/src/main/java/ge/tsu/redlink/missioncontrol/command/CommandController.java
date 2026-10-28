package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.contract.command.RoverCommand;
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
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@Tag(name = "Commands", description = "Send commands to rovers and follow their lifecycle")
@EnableConfigurationProperties(CommandProperties.class)
@RequiredArgsConstructor
public class CommandController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final CommandService commands;
    private final CommandProperties properties;
    private final SubmissionRateLimiter rateLimiter;

    /**
     * {@code 202 Accepted}: the command is queued, not done; the rover will only receive it after
     * the light-time delay. The body is returned as stored text so a replay is byte-for-byte the
     * original.
     */
    @Operation(summary = "Send a command (asynchronous, idempotent)",
            description = "Requires Idempotency-Key. Replays with the same key and body return the original "
                    + "response with Idempotent-Replayed: true; the same key with a different body is 422.")
    @ApiResponse(responseCode = "202", description = "Queued (or PENDING_APPROVAL for DRILL_SAMPLE)",
            headers = {@Header(name = HttpHeaders.LOCATION, description = "The command's status resource"),
                    @Header(name = HttpHeaders.RETRY_AFTER, description = "Seconds until polling is worthwhile"),
                    @Header(name = IDEMPOTENT_REPLAYED, description = "true when this is a replayed response")},
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CommandResponse.class)))
    @PostMapping(path = "/rovers/{roverId}/commands", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<String> submit(@PathVariable long roverId,
                                  @Parameter(description = "Unique per logical request, e.g. a UUID")
                                  @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                  @Valid @RequestBody RoverCommand command,
                                  @AuthenticationPrincipal Jwt jwt) {
        IdempotencyService.validateKey(idempotencyKey);
        rateLimiter.acquire(jwt.getSubject());
        Outcome outcome = commands.submit(roverId, command, jwt.getSubject(), idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.response().status())
                .location(URI.create(outcome.response().location()))
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(properties.retryAfter().toSeconds()))
                .contentType(MediaType.APPLICATION_JSON);
        if (outcome.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(outcome.response().body());
    }

    @Operation(summary = "Get a command", description = "_links shows what can be done next. Supports If-None-Match.")
    @GetMapping("/commands/{commandId}")
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<CommandResponse> get(@PathVariable long commandId) {
        return respond(commands.get(commandId));
    }

    @Operation(summary = "List a rover's commands, newest first")
    @GetMapping("/rovers/{roverId}/commands")
    @PreAuthorize("hasRole('VIEWER')")
    PagedModel<CommandResponse> list(@PathVariable long roverId,
                                     @RequestParam(required = false) CommandStatus status,
                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return new PagedModel<>(commands.list(roverId, status, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"))));
    }

    @Operation(summary = "Cancel a command that has not been transmitted",
            description = "200 (also when already cancelled); 409 once it is on its way to Mars.")
    @PostMapping("/commands/{commandId}/cancel")
    @PreAuthorize("hasRole('OPERATOR')")
    ResponseEntity<CommandResponse> cancel(@PathVariable long commandId, @AuthenticationPrincipal Jwt jwt) {
        return respond(commands.cancel(commandId, jwt.getSubject()));
    }

    @Operation(summary = "Approve a command waiting for approval (FLIGHT_DIRECTOR, not its creator)",
            description = "200 (also when already approved); 403 four-eyes-violation for your own command; "
                    + "409 if rejected, cancelled or not waiting; 422 if Earth's last knowledge rules it out.")
    @PostMapping("/commands/{commandId}/approve")
    @PreAuthorize("hasRole('FLIGHT_DIRECTOR')")
    ResponseEntity<CommandResponse> approve(@PathVariable long commandId, @AuthenticationPrincipal Jwt jwt) {
        return respond(commands.approve(commandId, jwt.getSubject()));
    }

    @Operation(summary = "Reject a command waiting for approval (FLIGHT_DIRECTOR)",
            description = "200 (also when already rejected); 409 if approved, cancelled or not waiting.")
    @PostMapping("/commands/{commandId}/reject")
    @PreAuthorize("hasRole('FLIGHT_DIRECTOR')")
    ResponseEntity<CommandResponse> reject(@PathVariable long commandId,
                                           @Valid @RequestBody(required = false) RejectRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return respond(commands.reject(commandId, jwt.getSubject(), request == null ? null : request.reason()));
    }

    /** @param reason shown to the operator who asked; optional */
    record RejectRequest(@Size(max = 500) String reason) {
    }

    private static ResponseEntity<CommandResponse> respond(Tagged<CommandResponse> command) {
        return ResponseEntity.ok().eTag(command.etag()).cacheControl(REVALIDATE).body(command.body());
    }
}
