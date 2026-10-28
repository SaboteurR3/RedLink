package ge.tsu.redlink.missioncontrol.site;

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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SitesIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;

    private String viewer;

    @BeforeEach
    void token() {
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
    }

    @Test
    void siteMetadataDescribesTheGrid() throws Exception {
        mvc.perform(get("/api/v1/sites/jezero-sim").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.width").value(64))
                .andExpect(jsonPath("$.height").value(64))
                .andExpect(jsonPath("$.maxElevationMeters").value(4.0))
                .andExpect(jsonPath("$.start.x").value(32));
    }

    @Test
    void heightmapIsAPngWithAContentETagAndCanBeCachedForADay() throws Exception {
        byte[] png = mvc.perform(get("/api/v1/sites/jezero-sim/heightmap").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=86400, private"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn().getResponse().getContentAsByteArray();

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isEqualTo(64);
        assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
    }

    @Test
    void revalidationIs304() throws Exception {
        String etag = mvc.perform(get("/api/v1/sites/jezero-sim/surface").header(HttpHeaders.AUTHORIZATION, viewer))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/api/v1/sites/jezero-sim/surface").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void heightmapAndSurfaceHaveDifferentETags() throws Exception {
        String heightmap = mvc.perform(get("/api/v1/sites/jezero-sim/heightmap").header(HttpHeaders.AUTHORIZATION, viewer))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        String surface = mvc.perform(get("/api/v1/sites/jezero-sim/surface").header(HttpHeaders.AUTHORIZATION, viewer))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(heightmap).isNotEqualTo(surface);
    }

    @Test
    void unknownAndMalformedSitesAre404() throws Exception {
        mvc.perform(get("/api/v1/sites/olympus-mons").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/site-not-found"));
        mvc.perform(get("/api/v1/sites/Jezero_Sim/heightmap").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound());
    }

    @Test
    void sitesRequireAToken() throws Exception {
        mvc.perform(get("/api/v1/sites/jezero-sim/heightmap")).andExpect(status().isUnauthorized());
    }
}
