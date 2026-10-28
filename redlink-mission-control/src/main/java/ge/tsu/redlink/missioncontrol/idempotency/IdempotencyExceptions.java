package ge.tsu.redlink.missioncontrol.idempotency;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * Misuse of the {@code Idempotency-Key} header (draft-ietf-httpapi-idempotency-key-header §2.7).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IdempotencyExceptions {

    /** 400: the header is required but missing, or not a valid key. */
    public static class InvalidIdempotencyKeyException extends ApiException {

        public InvalidIdempotencyKeyException(String detail) {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_IDEMPOTENCY_KEY, "Invalid Idempotency-Key", detail);
        }
    }

    /** 422: the key was already used for a different request. */
    public static class IdempotencyKeyReusedException extends ApiException {

        public IdempotencyKeyReusedException() {
            super(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key reused",
                    "This Idempotency-Key was already used for a different request; use a new key for a new request");
        }
    }

    /** 409: the original request with this key has not finished (only if it never completed). */
    public static class IdempotencyKeyInProgressException extends ApiException {

        public IdempotencyKeyInProgressException() {
            super(HttpStatus.CONFLICT, ProblemTypes.IDEMPOTENCY_KEY_IN_PROGRESS, "Request in progress",
                    "A request with this Idempotency-Key is still being processed; retry shortly");
        }
    }
}
