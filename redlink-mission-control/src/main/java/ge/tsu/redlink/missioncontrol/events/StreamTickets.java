package ge.tsu.redlink.missioncontrol.events;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use tickets that let a browser open an SSE stream. {@code EventSource} cannot
 * send an {@code Authorization} header, and a JWT in a URL would end up in logs and browser history;
 * a ticket in the URL is worthless seconds later. In memory: enough for one instance.
 */
@Component
@EnableConfigurationProperties(EventProperties.class)
@RequiredArgsConstructor
public class StreamTickets {

    private static final int PURGE_THRESHOLD = 1_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final EventProperties properties;

    /** A ticket for the caller (same user, same roles) and this rover only. */
    public IssuedTicket issue(Authentication caller, long roverId) {
        Instant now = clock.instant();
        if (tickets.size() > PURGE_THRESHOLD) {
            tickets.values().removeIf(ticket -> !now.isBefore(ticket.expiresAt()));
        }
        byte[] secret = new byte[32]; // 256 bits: not guessable
        random.nextBytes(secret);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(properties.ticketTtl());
        tickets.put(value, new Ticket(caller.getName(), List.copyOf(caller.getAuthorities()), roverId, expiresAt));
        return new IssuedTicket(value, expiresAt);
    }

    /**
     * Uses up a ticket. Removal is atomic, so of two concurrent attempts only one gets the ticket.
     *
     * @return the ticket if it exists, has not expired and was issued for this rover
     */
    public Optional<Ticket> redeem(String value, long roverId) {
        Ticket ticket = tickets.remove(value);
        if (ticket == null || !clock.instant().isBefore(ticket.expiresAt()) || ticket.roverId() != roverId) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    public record Ticket(String username, List<GrantedAuthority> authorities, long roverId, Instant expiresAt) {
    }

    public record IssuedTicket(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "IssuedTicket[value=***, expiresAt=" + expiresAt + "]";
        }
    }
}
