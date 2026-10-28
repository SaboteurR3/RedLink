package ge.tsu.redlink.missioncontrol.web;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * General-purpose {@link ApiException}s shared by several features.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiExceptions {

    /** 404 with a resource-specific problem type. */
    public static class NotFoundException extends ApiException {

        public NotFoundException(URI type, String detail) {
            super(HttpStatus.NOT_FOUND, type, "Not found", detail);
        }
    }

    /** 400: the request is well-formed JSON but breaks a rule; {@code errors} lists each problem. */
    public static class InvalidRequestException extends ApiException {

        @Getter
        private final List<String> errors;

        public InvalidRequestException(String detail, List<String> errors) {
            super(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation failed", detail);
            this.errors = List.copyOf(errors);
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("errors", errors);
        }
    }

    /**
     * 422: understood and valid, but cannot be done in the current state (e.g. the rover was last
     * reported in safe mode).
     */
    public static class UnprocessableException extends ApiException {

        public UnprocessableException(URI type, String title, String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, type, title, detail);
        }
    }

    /** 409: conflicts with the resource's current state. */
    public static class ConflictException extends ApiException {

        public ConflictException(URI type, String title, String detail) {
            super(HttpStatus.CONFLICT, type, title, detail);
        }
    }

    /** 428 (RFC 6585): a write that must be conditional was sent without {@code If-Match}. */
    public static class PreconditionRequiredException extends ApiException {

        public PreconditionRequiredException(String detail) {
            super(HttpStatus.PRECONDITION_REQUIRED, ProblemTypes.PRECONDITION_REQUIRED, "Precondition required", detail);
        }
    }

    /** 412: {@code If-Match} does not match the current representation (someone else changed it). */
    public static class PreconditionFailedException extends ApiException {

        public PreconditionFailedException(String detail) {
            super(HttpStatus.PRECONDITION_FAILED, ProblemTypes.PRECONDITION_FAILED, "Precondition failed", detail);
        }
    }
}
