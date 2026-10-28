package ge.tsu.redlink.missioncontrol.dsn;

import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.ConjunctionResponse;
import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.LinkStatus;
import ge.tsu.redlink.missioncontrol.dsn.LinkDtos.WindowStatus;
import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.rover.Rover;
import ge.tsu.redlink.missioncontrol.rover.RoverRepository;
import ge.tsu.redlink.missioncontrol.uplink.UplinkProperties;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.PreconditionFailedException;
import ge.tsu.redlink.missioncontrol.web.EntityTags;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Whether the deep-space link is usable: relay-orbiter windows (a fixed schedule) and solar
 * conjunction (switched by a flight director).
 */
@Service
@EnableConfigurationProperties(DsnProperties.class)
@Slf4j
@RequiredArgsConstructor
public class DsnService {

    private final LinkStateRepository states;
    private final RoverRepository rovers;
    private final RoverEventPublisher events;
    private final DsnProperties properties;
    private final UplinkProperties uplink;
    private final Clock clock;

    /** Commands may leave Earth now: a window is open and the Sun is not in the way. */
    @Transactional(readOnly = true)
    public boolean uplinkOpen(Instant now) {
        return LinkWindows.at(properties.windows(), now).open() && !conjunctionActive();
    }

    @Transactional(readOnly = true)
    public boolean conjunctionActive() {
        return state().isConjunctionActive();
    }

    /**
     * @throws LinkUnavailableException (503 + Retry-After) during a solar conjunction
     */
    @Transactional(readOnly = true)
    public void requireDownlink() {
        if (conjunctionActive()) {
            throw new LinkUnavailableException(properties.conjunctionRetryAfter());
        }
    }

    @Transactional(readOnly = true)
    public LinkStatus status() {
        return status(state(), now());
    }

    @Transactional(readOnly = true)
    public Tagged<ConjunctionResponse> conjunction() {
        return tagged(state());
    }

    /**
     * Sets the conjunction; setting the value it already has changes nothing (so PUT is idempotent).
     *
     * @param ifMatch optional; when given it must match the current ETag
     * @throws PreconditionFailedException if {@code ifMatch} is stale
     */
    @Transactional
    public Tagged<ConjunctionResponse> setConjunction(boolean active, String user, String ifMatch) {
        LinkState state = state();
        if (ifMatch != null && !EntityTags.ifMatchSatisfied(ifMatch, EntityTags.of(state.getVersion()))) {
            throw new PreconditionFailedException("The link state was changed by someone else; fetch it again and retry");
        }
        Instant now = now();
        if (state.setConjunction(active, user, now)) {
            states.saveAndFlush(state);
            log.warn("{} {} the solar conjunction", user, active ? "started" : "ended");
            LinkStatus status = status(state, now);
            for (Rover rover : rovers.findAll()) {
                events.publish(rover.getId(), EventType.LINK, status);
            }
        }
        return tagged(state);
    }

    private LinkStatus status(LinkState state, Instant now) {
        LinkWindows.Window window = LinkWindows.at(properties.windows(), now);
        return new LinkStatus(uplink.oneWayDelay().toSeconds(),
                new WindowStatus(properties.windows().enabled(), window.open(), window.nextChangeAt()),
                ConjunctionResponse.of(state), window.open() && !state.isConjunctionActive());
    }

    private static Tagged<ConjunctionResponse> tagged(LinkState state) {
        return new Tagged<>(ConjunctionResponse.of(state), EntityTags.of(state.getVersion()));
    }

    private LinkState state() {
        return states.findById(LinkState.ID).orElseThrow(() -> new IllegalStateException("link_state row missing"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
