package ge.tsu.redlink.roversim.security;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.roversim.TimeConfig;
import ge.tsu.redlink.roversim.web.ProblemResponses;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rover accepts nothing but signed link packets.
 */
@WebMvcTest(controllers = RoverSecurityTest.ProbeController.class)
@Import({RoverSecurityConfig.class, TimeConfig.class, ProblemResponses.class,
        // Explicit: Boot's test scanning skips classes nested in test classes.
        RoverSecurityTest.ProbeController.class})
@ActiveProfiles("test")
class RoverSecurityTest {

    private static final String PACKET = "{\"packetId\":\"p-1\"}";

    @Autowired
    MockMvc mvc;
    @Autowired
    PacketSigner packetSigner;

    @Test
    void signedPacketIsAccepted() throws Exception {
        mvc.perform(post("/flight/test-echo")
                        .header(PacketSigner.HEADER, packetSigner.sign(PACKET.getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_JSON).content(PACKET))
                .andExpect(status().isOk())
                .andExpect(content().string(PACKET));
    }

    @Test
    void unsignedPacketIsRejected() throws Exception {
        mvc.perform(post("/flight/test-echo").contentType(MediaType.APPLICATION_JSON).content(PACKET))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-link-signature"));
    }

    @Test
    void tamperedPacketIsRejected() throws Exception {
        mvc.perform(post("/flight/test-echo")
                        .header(PacketSigner.HEADER, packetSigner.sign(PACKET.getBytes(StandardCharsets.UTF_8)))
                        .contentType(MediaType.APPLICATION_JSON).content(PACKET.replace("p-1", "p-2")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokensMeanNothingHere() throws Exception {
        mvc.perform(post("/flight/test-echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer anything")
                        .contentType(MediaType.APPLICATION_JSON).content(PACKET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void everythingElseLooksLikeItDoesNotExist() throws Exception {
        mvc.perform(get("/internal/state"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/not-found"));
    }

    @RestController
    static class ProbeController {

        @PostMapping("/flight/test-echo")
        String echo(@RequestBody String body) {
            return body;
        }

        /** Exists, but must still be unreachable from outside. */
        @GetMapping("/internal/state")
        String state() {
            return "secret";
        }
    }
}
