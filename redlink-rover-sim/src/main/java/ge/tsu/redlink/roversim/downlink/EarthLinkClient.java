package ge.tsu.redlink.roversim.downlink;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.roversim.tracing.LinkTracing;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * The rover's transmitter: one signed POST per telemetry packet to mission-control.
 */
@Component
public class EarthLinkClient {

    /**
     * Statuses that say the packet itself is unacceptable; sending it again cannot succeed.
     * Everything else (5xx, 404 during a deploy, 401 during a secret rotation, network errors)
     * may pass later and is retried.
     */
    private static final Set<Integer> PERMANENT = Set.of(400, 409, 413, 422);
    private static final String DOWNLINK_PATH = "/dsn/downlink";

    private final RestClient restClient;
    private final PacketSigner signer;
    private final Clock clock;
    private final LinkTracing tracing;

    public EarthLinkClient(DownlinkProperties properties, PacketSigner signer, Clock clock, LinkTracing tracing) {
        HttpClient httpClient = HttpClient.newBuilder()
                // The JDK client defaults to HTTP/2 and, over plain http, sends an "Upgrade: h2c"
                // request; Tomcat does not accept that upgrade on a POST with a body and the
                // connection fails. The link does not need HTTP/2.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.earthUrl())
                .requestFactory(requestFactory)
                .build();
        this.signer = signer;
        this.clock = clock;
        this.tracing = tracing;
    }

    public Delivery send(String packetJson) {
        byte[] body = packetJson.getBytes(StandardCharsets.UTF_8);
        try {
            Answer answer = restClient.post()
                    .uri(DOWNLINK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PacketSigner.HEADER, signer.sign(body))
                    .headers(headers -> {
                        // Earth's server span joins the trace (the packet carries it too, signed).
                        String traceparent = tracing.currentTraceparent();
                        if (traceparent != null) {
                            headers.set(LinkTracing.HEADER, traceparent);
                        }
                    })
                    .body(body)
                    // Judge the status ourselves instead of turning 4xx/5xx into exceptions.
                    .exchange((request, response) -> new Answer(response.getStatusCode(),
                            response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
            HttpStatusCode status = answer.status();
            if (status.is2xxSuccessful()) {
                return new Delivery(Outcome.DELIVERED, "HTTP " + status.value(), null);
            }
            if (PERMANENT.contains(status.value())) {
                return new Delivery(Outcome.REJECTED, "HTTP " + status.value(), null);
            }
            // 503 (e.g. solar conjunction) and 429 may say when trying again is worthwhile.
            Duration retryAfter = status.value() == 503 || status.value() == 429
                    ? RetryAfter.parse(answer.retryAfter(), clock.instant()).orElse(null) : null;
            return new Delivery(Outcome.RETRY, "HTTP " + status.value(), retryAfter);
        } catch (RestClientException e) {
            return new Delivery(Outcome.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    public enum Outcome {
        DELIVERED,
        REJECTED,
        RETRY
    }

    /** @param retryAfter the server's hint when to try again (RETRY only), or null */
    public record Delivery(Outcome outcome, String detail, Duration retryAfter) {
    }

    private record Answer(HttpStatusCode status, String retryAfter) {
    }
}
