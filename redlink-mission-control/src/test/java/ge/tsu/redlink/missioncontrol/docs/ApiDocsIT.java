package ge.tsu.redlink.missioncontrol.docs;

import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ApiDocsIT {

    @Autowired
    MockMvc mvc;

    @Test
    void openApiDocumentIsPublicAndDescribesTheBearerScheme() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("RedLink Mission Control API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/rovers/{id}'].patch").exists())
                // login is public: its operation overrides the global security requirement
                .andExpect(jsonPath("$.paths['/api/v1/auth/token'].post.security").isEmpty())
                // machine-to-machine link endpoints are not part of the public API
                .andExpect(jsonPath("$.paths['/dsn/downlink']").doesNotExist());
    }

    /** Generated clients must send "MOVE" (the wire name), not "Move" (the Java class name). */
    @Test
    void discriminatorValuesAreTheWireNames() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.RoverCommand.discriminator.mapping.MOVE")
                        .value("#/components/schemas/Move"))
                .andExpect(jsonPath("$.components.schemas.RoverCommand.discriminator.mapping.TAKE_PHOTO")
                        .value("#/components/schemas/TakePhoto"))
                .andExpect(jsonPath("$.components.schemas.TelemetryPayload.discriminator.mapping.ROVER_STATE")
                        .value("#/components/schemas/RoverState"));
    }

    @Test
    void swaggerUiLoadsWithAPageFriendlyContentSecurityPolicy() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("default-src 'none'"))));
    }
}
