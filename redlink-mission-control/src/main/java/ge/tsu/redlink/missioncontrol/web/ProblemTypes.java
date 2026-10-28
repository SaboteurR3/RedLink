package ge.tsu.redlink.missioncontrol.web;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.URI;

/**
 * Stable RFC 9457 problem {@code type} URIs. Clients branch on these, never on titles or details.
 * {@code redlink.example} is a reserved example domain: the URIs identify, they don't resolve.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProblemTypes {

    public static final String BASE = "https://redlink.example/problems/";

    public static final URI UNAUTHENTICATED = of("unauthenticated");
    public static final URI FORBIDDEN = of("forbidden");
    public static final URI INVALID_CREDENTIALS = of("invalid-credentials");
    public static final URI TOO_MANY_LOGIN_ATTEMPTS = of("too-many-login-attempts");
    public static final URI INVALID_LINK_SIGNATURE = of("invalid-link-signature");
    public static final URI PACKET_TOO_LARGE = of("packet-too-large");

    public static final URI VALIDATION_ERROR = of("validation-error");
    public static final URI MALFORMED_REQUEST = of("malformed-request");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI ROVER_NOT_FOUND = of("rover-not-found");
    public static final URI SITE_NOT_FOUND = of("site-not-found");
    public static final URI PRECONDITION_REQUIRED = of("precondition-required");
    public static final URI PRECONDITION_FAILED = of("precondition-failed");
    public static final URI INTERNAL_ERROR = of("internal-error");

    public static final URI INVALID_IDEMPOTENCY_KEY = of("invalid-idempotency-key");
    public static final URI IDEMPOTENCY_KEY_REUSED = of("idempotency-key-reused");
    public static final URI IDEMPOTENCY_KEY_IN_PROGRESS = of("idempotency-key-in-progress");
    public static final URI COMMAND_NOT_FOUND = of("command-not-found");
    public static final URI COMMAND_NOT_CANCELLABLE = of("command-not-cancellable");
    public static final URI COMMAND_NOT_PENDING_APPROVAL = of("command-not-pending-approval");
    public static final URI FOUR_EYES_VIOLATION = of("four-eyes-violation");
    public static final URI WEBHOOK_NOT_FOUND = of("webhook-not-found");
    public static final URI WEBHOOK_LIMIT = of("webhook-limit");
    public static final URI ROVER_IN_SAFE_MODE = of("rover-in-safe-mode");
    public static final URI INSUFFICIENT_BATTERY = of("insufficient-battery");
    public static final URI UNKNOWN_ROVER = of("unknown-rover");
    public static final URI INVALID_STREAM_TICKET = of("invalid-stream-ticket");
    public static final URI TOO_MANY_SUBSCRIBERS = of("too-many-subscribers");
    public static final URI PHOTO_NOT_FOUND = of("photo-not-found");
    public static final URI MISSION_NOT_FOUND = of("mission-not-found");
    public static final URI MISSION_PLAN_INFEASIBLE = of("mission-plan-infeasible");
    public static final URI MISSION_NOT_CANCELLABLE = of("mission-not-cancellable");
    public static final URI ROVER_BUSY = of("rover-busy");
    public static final URI LINK_UNAVAILABLE = of("link-unavailable");
    public static final URI METHOD_NOT_ALLOWED = of("method-not-allowed");
    public static final URI NOT_ACCEPTABLE = of("not-acceptable");
    public static final URI UNSUPPORTED_MEDIA_TYPE = of("unsupported-media-type");

    /** For errors that have no specific type of their own (e.g. Spring's built-in 405, 406, 415). */
    public static URI forStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 500 -> INTERNAL_ERROR;
            default -> of("http-" + status);
        };
    }

    public static URI of(String slug) {
        return URI.create(BASE + slug);
    }
}
