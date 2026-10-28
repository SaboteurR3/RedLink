package ge.tsu.redlink.missioncontrol.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Map;

/**
 * Base for exceptions that map to one specific Problem Details response. Features declare their
 * own subclasses; {@link ApiProblemHandler} renders all of them the same way.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final URI type;
    private final String title;

    protected ApiException(HttpStatus status, URI type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    /** Extra problem members (RFC 9457 extensions), e.g. a list of validation errors. */
    public Map<String, Object> properties() {
        return Map.of();
    }

    /** Extra response headers, e.g. {@code Retry-After} with a 503. */
    public Map<String, String> headers() {
        return Map.of();
    }
}
