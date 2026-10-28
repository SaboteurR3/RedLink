package ge.tsu.redlink.roversim.uplink;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Reasons the rover refuses to store an uplinked packet. All are permanent: retrying the same
 * packet gives the same answer.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class UplinkExceptions {

    public static class UnknownRoverException extends RuntimeException {

        public UnknownRoverException(long roverId) {
            super("No rover with id " + roverId + " is listening on this link");
        }
    }

    public static class UplinkConflictException extends RuntimeException {

        public enum Kind {
            /** The packet id was seen before, with different content. */
            PACKET_ID_REUSED,
            /** Another packet already occupies this (rover, sequence number) slot. */
            SEQUENCE_TAKEN
        }

        @Getter
        private final Kind kind;

        public UplinkConflictException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }
    }
}
