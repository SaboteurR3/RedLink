package ge.tsu.redlink.missioncontrol.dsn;

import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LinkDtos {

    /** @param active true while the Sun blocks the link */
    public record ConjunctionRequest(@NotNull Boolean active) {
    }

    /** @param changedBy who last changed it; null if never */
    public record ConjunctionResponse(boolean active, String changedBy, Instant changedAt) {

        static ConjunctionResponse of(LinkState state) {
            return new ConjunctionResponse(state.isConjunctionActive(), state.getChangedBy(), state.getChangedAt());
        }
    }

    /**
     * @param queueHoldSeconds how long a new command waits on Earth before it is sent (the cancel window)
     * @param uplinkOpen       whether queued commands can leave Earth right now (window open, no conjunction)
     */
    public record LinkStatus(long oneWayDelaySeconds, long queueHoldSeconds, WindowStatus window,
                             ConjunctionResponse conjunction, boolean uplinkOpen) {
    }

    /** @param nextChangeAt when the window closes (if open) or opens (if closed); null when windows are off */
    public record WindowStatus(boolean enabled, boolean open, Instant nextChangeAt) {
    }
}
