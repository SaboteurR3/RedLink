package ge.tsu.redlink.missioncontrol.rover;

import ge.tsu.redlink.missioncontrol.events.EventType;
import ge.tsu.redlink.missioncontrol.events.RoverEventPublisher;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.NotFoundException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.PreconditionFailedException;
import ge.tsu.redlink.missioncontrol.web.ApiExceptions.PreconditionRequiredException;
import ge.tsu.redlink.missioncontrol.web.EntityTags;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import ge.tsu.redlink.missioncontrol.web.Tagged;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class RoverService {

    private final RoverRepository rovers;
    private final RoverEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Page<RoverResponse> list(Pageable pageable) {
        return rovers.findAll(pageable).map(RoverResponse::of);
    }

    @Transactional(readOnly = true)
    public Tagged<RoverResponse> get(long id) {
        return tagged(find(id));
    }

    /**
     * Applies a merge patch if {@code ifMatch} matches the current version. The check and the
     * update run in one transaction, and {@code @Version} makes the UPDATE fail if another writer
     * committed in between, so no update is ever lost.
     *
     * @throws PreconditionRequiredException if {@code ifMatch} is missing
     * @throws PreconditionFailedException   if it does not match the current version
     */
    @Transactional
    public Tagged<RoverResponse> patch(long id, String ifMatch, RoverPatch patch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException(
                    "Send If-Match with the rover's current ETag, so concurrent edits cannot overwrite each other");
        }
        Rover rover = find(id);
        String current = EntityTags.of(rover.getVersion());
        if (!EntityTags.ifMatchSatisfied(ifMatch, current)) {
            throw new PreconditionFailedException("The rover was changed since you read it (current ETag " + current + ")");
        }
        if (patch.name().isPresent() && !patch.name().get().equals(rover.getName())) {
            rover.rename(patch.name().get(), clock.instant().truncatedTo(ChronoUnit.MICROS));
            rovers.saveAndFlush(rover); // version check + increment happen here, inside the transaction
            events.publish(rover.getId(), EventType.ROVER, RoverResponse.of(rover));
        }
        return tagged(rover);
    }

    private Rover find(long id) {
        return rovers.findById(id).orElseThrow(() ->
                new NotFoundException(ProblemTypes.ROVER_NOT_FOUND, "Rover " + id + " does not exist"));
    }

    private static Tagged<RoverResponse> tagged(Rover rover) {
        return new Tagged<>(RoverResponse.of(rover), EntityTags.of(rover.getVersion()));
    }
}
