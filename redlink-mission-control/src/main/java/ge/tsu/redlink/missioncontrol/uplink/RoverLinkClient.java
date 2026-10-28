package ge.tsu.redlink.missioncontrol.uplink;

import ge.tsu.redlink.contract.link.PacketSigner;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The Deep Space Network antenna: one signed POST per command packet to the rover.
 */
@Component
public class RoverLinkClient {

    /**
     * The rover's answers that say the packet itself is unacceptable (rover-sim answers only
     * permanent problems with these). Everything else may pass later and is retried.
     */
    private static final Set<Integer> PERMANENT = Set.of(400, 409, 413, 422);
    private static final String UPLINK_PATH = "/flight/uplink";

    private final RestClient restClient;
    private final PacketSigner signer;

    public RoverLinkClient(UplinkProperties properties, PacketSigner signer) {
        HttpClient httpClient = HttpClient.newBuilder()
                // Over plain http the JDK client would try an "Upgrade: h2c" that Tomcat cannot
                // handle for a POST with a body (found in ticket 07).
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.roverUrl())
                .requestFactory(requestFactory)
                .build();
        this.signer = signer;
    }

    public Delivery send(String packetJson) {
        byte[] body = packetJson.getBytes(StandardCharsets.UTF_8);
        try {
            HttpStatusCode status = restClient.post()
                    .uri(UPLINK_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(PacketSigner.HEADER, signer.sign(body))
                    .body(body)
                    .exchange((request, response) -> response.getStatusCode());
            if (status.is2xxSuccessful()) {
                return new Delivery(Outcome.DELIVERED, "HTTP " + status.value());
            }
            return new Delivery(PERMANENT.contains(status.value()) ? Outcome.REJECTED : Outcome.RETRY, "HTTP " + status.value());
        } catch (RestClientException e) {
            return new Delivery(Outcome.RETRY, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public enum Outcome {
        DELIVERED,
        REJECTED,
        RETRY
    }

    public record Delivery(Outcome outcome, String detail) {
    }
}
