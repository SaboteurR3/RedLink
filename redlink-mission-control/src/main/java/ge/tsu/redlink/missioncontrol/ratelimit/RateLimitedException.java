package ge.tsu.redlink.missioncontrol.ratelimit;

import ge.tsu.redlink.missioncontrol.web.ApiException;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** 429 with {@code Retry-After}: when the next request will be accepted. */
@Getter
public class RateLimitedException extends ApiException {

    private final long retryAfterSeconds;

    RateLimitedException(int capacity, String per, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, "Too many requests",
                "At most " + capacity + " commands and missions per " + per + "; try again in "
                        + retryAfterSeconds + " s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public Map<String, String> headers() {
        return Map.of(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    }
}
