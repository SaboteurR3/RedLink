package ge.tsu.redlink.missioncontrol.specs;

import ge.tsu.redlink.contract.rover.RoverSpecs;
import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SpecsIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void specSheetIsTheRoversOwnNumbersAndCacheable() throws Exception {
        String viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
        String etag = mvc.perform(get("/api/v1/specs").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, private"))
                .andExpect(jsonPath("$.moveCostPerMeterSand").value(RoverSpecs.MOVE_COST_SAND))
                .andExpect(jsonPath("$.drillMinBattery").value(RoverSpecs.DRILL_MIN_BATTERY))
                .andExpect(jsonPath("$.rotateSecondsPerQuarterTurn").value(2.0))
                .andExpect(jsonPath("$.drillSeconds").value(15.0))
                .andExpect(jsonPath("$.solSeconds").value(720.0))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mvc.perform(get("/api/v1/specs").header(HttpHeaders.AUTHORIZATION, viewer).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/specs")).andExpect(status().isUnauthorized());
    }
}
