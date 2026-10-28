package ge.tsu.redlink.missioncontrol.events;

import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authenticates {@code GET /api/v1/rovers/{id}/events?ticket=...} with a stream ticket. Any other
 * request passes through untouched (bearer tokens are handled by the resource server).
 *
 * <p>Not a Spring bean on purpose: Boot would register a bean filter for every URL outside the
 * security chain.
 */
@RequiredArgsConstructor
public class StreamTicketFilter extends OncePerRequestFilter {

    private static final Pattern EVENTS_PATH = Pattern.compile("^/api/v1/rovers/(\\d{1,18})/events$");
    static final String TICKET_PARAM = "ticket";

    private final StreamTickets tickets;
    private final ProblemResponses problems;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ticket = request.getParameter(TICKET_PARAM);
        Matcher path = EVENTS_PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (ticket == null || !"GET".equals(request.getMethod()) || !path.matches()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<StreamTickets.Ticket> redeemed = tickets.redeem(ticket, Long.parseLong(path.group(1)));
        if (redeemed.isEmpty()) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_STREAM_TICKET,
                    "Invalid stream ticket", "The stream ticket is unknown, expired, already used or for another rover; "
                            + "get a new one with POST .../events/tickets");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                redeemed.get().username(), null, redeemed.get().authorities()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
