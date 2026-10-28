package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.missioncontrol.rover.RoverService;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/rovers/{roverId}/events")
@Tag(name = "Events", description = "Live updates of a rover over Server-Sent Events")
@RequiredArgsConstructor
public class EventsController {

    private final SseHub hub;
    private final StreamTickets tickets;
    private final RoverService rovers;

    @Operation(summary = "Get a single-use stream ticket",
            description = "Browsers' EventSource cannot send Authorization headers: open streamUrl instead "
                    + "within the ticket's lifetime. The ticket works once.")
    @PostMapping("/tickets")
    @PreAuthorize("hasRole('VIEWER')")
    ResponseEntity<TicketResponse> ticket(@PathVariable long roverId, Authentication caller) {
        rovers.get(roverId); // 404 for unknown rovers
        StreamTickets.IssuedTicket ticket = tickets.issue(caller, roverId);
        String streamUrl = "/api/v1/rovers/" + roverId + "/events?" + StreamTicketFilter.TICKET_PARAM + "=" + ticket.value();
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()) // the ticket is a credential
                .body(new TicketResponse(ticket.value(), ticket.expiresAt(), streamUrl));
    }

    @Operation(summary = "Stream the rover's events (text/event-stream)",
            description = "Events: rover (snapshot first), command, uplink, rover-event, reset. Authenticate with "
                    + "?ticket= (browsers) or a bearer token. Resume with Last-Event-ID or ?lastEventId=.")
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('VIEWER')")
    SseEmitter stream(@PathVariable long roverId,
                      @Parameter(description = "Sent by EventSource automatically when it reconnects")
                      @RequestHeader(name = "Last-Event-ID", required = false) String lastEventIdHeader,
                      @Parameter(description = "For a new EventSource, which cannot set Last-Event-ID")
                      @RequestParam(required = false) Long lastEventId,
                      HttpServletResponse response) {
        rovers.get(roverId); // 404 for unknown rovers
        // Reverse proxies such as nginx must not buffer the stream.
        response.setHeader("X-Accel-Buffering", "no");
        return hub.subscribe(roverId, resumePoint(lastEventIdHeader, lastEventId));
    }

    private static Long resumePoint(String header, Long param) {
        if (header == null || header.isBlank()) {
            return param;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Last-Event-ID must be an event id", List.of("Last-Event-ID: not a number"));
        }
    }

    /**
     * @param streamUrl ready to pass to {@code new EventSource(...)}
     */
    public record TicketResponse(String ticket, Instant expiresAt, String streamUrl) {

        @Override
        public String toString() {
            return "TicketResponse[ticket=***, expiresAt=" + expiresAt + "]";
        }
    }
}
