package ge.tsu.redlink.contract.link;

import lombok.Getter;

/**
 * A link packet failed authentication. The {@link Reason} is for logs only; receivers answer
 * every failure the same way so a sender learns nothing about what was wrong.
 */
@Getter
public class SignatureVerificationException extends RuntimeException {

    public enum Reason {
        MISSING,
        MALFORMED,
        MISMATCH,
        OUTSIDE_TOLERANCE
    }

    private final Reason reason;

    public SignatureVerificationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
