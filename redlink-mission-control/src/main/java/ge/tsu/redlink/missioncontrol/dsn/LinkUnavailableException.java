package ge.tsu.redlink.missioncontrol.dsn;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/**
 * 503 during a solar conjunction: nothing gets through, but it will again. {@code Retry-After}
 * tells the sender when trying again is worthwhile (RFC 9110 §10.2.3).
 */
class LinkUnavailableException extends ApiException {

    private final Duration retryAfter;

    LinkUnavailableException(Duration retryAfter) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.LINK_UNAVAILABLE, "Link unavailable",
                "Solar conjunction: the Sun blocks the link; keep the packet and send it again later");
        this.retryAfter = retryAfter;
    }

    @Override
    public Map<String, String> headers() {
        return Map.of(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()));
    }
}
