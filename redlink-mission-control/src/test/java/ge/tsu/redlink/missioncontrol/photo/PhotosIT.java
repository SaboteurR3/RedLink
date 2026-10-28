package ge.tsu.redlink.missioncontrol.photo;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.rover.Camera;
import ge.tsu.redlink.contract.rover.Heading;
import ge.tsu.redlink.contract.telemetry.TelemetryPacket;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos arriving as signed PHOTO telemetry and served with content negotiation, against real
 * PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PhotosIT {

    private static final Instant MARS_T = Instant.parse("2026-10-26T12:00:10Z");

    @Autowired
    MockMvc mvc;
    @Autowired
    PacketSigner signer;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JwtEncoder jwtEncoder;

    private byte[] png;

    @BeforeEach
    void reset() throws IOException {
        jdbc.sql("DELETE FROM photos").update();
        jdbc.sql("DELETE FROM telemetry").update();
        png = png(4, 3);
    }

    // --- downlink ------------------------------------------------------------------------------

    @Test
    void photoTelemetryIsStoredOnceAndTheHistoryKeepsOnlyItsMetadata() throws Exception {
        TelemetryPacket packet = photoPacket(MARS_T);

        send(packet).andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(false));
        send(packet).andExpect(status().isAccepted()).andExpect(jsonPath("$.duplicate").value(true));

        assertThat(jdbc.sql("SELECT count(*) FROM photos").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM photos").query(UUID.class).single()).isEqualTo(packet.packetId());
        String stored = jdbc.sql("SELECT payload::text FROM telemetry WHERE type = 'PHOTO'").query(String.class).single();
        assertThat(stored).contains("\"camera\": \"MASTCAM\"").doesNotContain("imageBase64");

        mvc.perform(get("/api/v1/rovers/1/telemetry?type=PHOTO").header(HttpHeaders.AUTHORIZATION, viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].commandId").value(77))
                .andExpect(jsonPath("$.content[0].payload.heading").value("EAST"))
                .andExpect(jsonPath("$.content[0].payload.imageBase64").doesNotExist());
    }

    @Test
    void somethingThatIsNotAPngIsRefusedAndNothingIsStored() throws Exception {
        String notPng = Base64.getEncoder().encodeToString("hello, mars".getBytes());
        send(new TelemetryPacket(UUID.randomUUID(), 1, MARS_T, photo(notPng))).andExpect(status().isBadRequest());

        // Passes the signature check of the payload format but has no IHDR chunk.
        byte[] broken = Arrays.copyOf(png, 24);
        broken[12] = 'X';
        send(new TelemetryPacket(UUID.randomUUID(), 1, MARS_T, photo(Base64.getEncoder().encodeToString(broken))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("payload.imageBase64 is not a PNG"));

        assertThat(jdbc.sql("SELECT count(*) FROM photos").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM telemetry").query(Long.class).single()).as("rolled back").isZero();
    }

    // --- list ----------------------------------------------------------------------------------

    @Test
    void roverPhotosAreListedNewestFirstWithoutTheImage() throws Exception {
        TelemetryPacket older = photoPacket(MARS_T);
        TelemetryPacket newer = photoPacket(MARS_T.plusSeconds(60));
        send(older);
        send(newer);

        mvc.perform(get("/api/v1/rovers/1/photos?size=1").header(HttpHeaders.AUTHORIZATION, viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(newer.packetId().toString()))
                .andExpect(jsonPath("$.content[0].width").value(4))
                .andExpect(jsonPath("$.content[0].height").value(3))
                .andExpect(jsonPath("$.content[0].sizeBytes").value(png.length))
                .andExpect(jsonPath("$.content[0].takenAt").value("2026-10-26T12:01:10Z"))
                .andExpect(jsonPath("$.content[0].imageBase64").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    void photosOfAnUnknownRoverAre404() throws Exception {
        mvc.perform(get("/api/v1/rovers/999/photos").header(HttpHeaders.AUTHORIZATION, viewer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rover-not-found"));
    }

    // --- content negotiation -------------------------------------------------------------------

    @Test
    void jsonIsTheDefaultRepresentation() throws Exception {
        UUID id = sendPhoto();

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[]{
                photo(id), photo(id).accept(MediaType.ALL), photo(id).accept(MediaType.APPLICATION_JSON)}) {
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(variesByAccept())
                    .andExpect(header().string(HttpHeaders.ETAG, startsWith("\"")))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.camera").value("MASTCAM"))
                    .andExpect(jsonPath("$._links.self.href").value("/api/v1/photos/" + id))
                    .andExpect(jsonPath("$._links.image.href").value("/api/v1/photos/" + id))
                    .andExpect(jsonPath("$._links.image.type").value("image/png"))
                    .andExpect(jsonPath("$._links.command.href").value("/api/v1/commands/77"));
        }
    }

    @Test
    void acceptImagePngReturnsTheImageItself() throws Exception {
        UUID id = sendPhoto();

        byte[] body = mvc.perform(photo(id).accept(MediaType.IMAGE_PNG))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(variesByAccept())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, private, immutable"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"photo-" + id + ".png\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, png.length))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).isEqualTo(png);
    }

    @Test
    void eachRepresentationHasItsOwnEntityTagAndAnswers304() throws Exception {
        UUID id = sendPhoto();
        String jsonTag = mvc.perform(photo(id)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String pngTag = mvc.perform(photo(id).accept(MediaType.IMAGE_PNG)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(jsonTag).isNotEqualTo(pngTag);
        mvc.perform(photo(id).header(HttpHeaders.IF_NONE_MATCH, jsonTag))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));
        mvc.perform(photo(id).accept(MediaType.IMAGE_PNG).header(HttpHeaders.IF_NONE_MATCH, pngTag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, pngTag));
        mvc.perform(photo(id).accept(MediaType.IMAGE_PNG).header(HttpHeaders.IF_NONE_MATCH, jsonTag))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedRepresentationIs406() throws Exception {
        UUID id = sendPhoto();

        mvc.perform(photo(id).accept(MediaType.TEXT_PLAIN)).andExpect(status().isNotAcceptable());
        mvc.perform(photo(id).accept(MediaType.IMAGE_JPEG)).andExpect(status().isNotAcceptable());
    }

    @Test
    void qualityValuesPickThePreferredRepresentation() throws Exception {
        UUID id = sendPhoto();

        mvc.perform(photo(id).header(HttpHeaders.ACCEPT, "application/json;q=0.5, image/png"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(photo(id).header(HttpHeaders.ACCEPT, "image/png;q=0.5, application/json"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void unknownPhotoIs404InBothRepresentations() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(photo(id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/photo-not-found"));
        mvc.perform(photo(id).accept(MediaType.IMAGE_PNG)).andExpect(status().isNotFound());
    }

    @Test
    void malformedIdIs400AndMissingTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/photos/not-a-uuid").header(HttpHeaders.AUTHORIZATION, viewer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/photos/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/rovers/1/photos")).andExpect(status().isUnauthorized());
    }

    // --- helpers -------------------------------------------------------------------------------

    /** CORS adds its own Vary line (Origin, ...), so look at all of them. */
    private static ResultMatcher variesByAccept() {
        return result -> assertThat(result.getResponse().getHeaders(HttpHeaders.VARY))
                .anySatisfy(vary -> assertThat(vary).contains(HttpHeaders.ACCEPT));
    }

    private MockHttpServletRequestBuilder photo(UUID id) {
        return get("/api/v1/photos/" + id).header(HttpHeaders.AUTHORIZATION, viewer());
    }

    private String viewer() {
        return TestTokens.bearer(jwtEncoder, "VIEWER");
    }

    private UUID sendPhoto() throws Exception {
        TelemetryPacket packet = photoPacket(MARS_T);
        send(packet).andExpect(status().isAccepted());
        return packet.packetId();
    }

    private TelemetryPacket photoPacket(Instant roverTime) {
        return new TelemetryPacket(UUID.randomUUID(), 1, roverTime, photo(Base64.getEncoder().encodeToString(png)));
    }

    private static TelemetryPayload.Photo photo(String imageBase64) {
        return new TelemetryPayload.Photo(77, Camera.MASTCAM, 32, 30, Heading.EAST, imageBase64);
    }

    /** Signed exactly as rover-sim signs its downlink. */
    private ResultActions send(TelemetryPacket packet) throws Exception {
        byte[] body = jsonMapper.writeValueAsBytes(packet);
        return mvc.perform(post("/dsn/downlink").contentType(MediaType.APPLICATION_JSON)
                .header(PacketSigner.HEADER, signer.sign(body)).content(body));
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 1, 0xC07040);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
