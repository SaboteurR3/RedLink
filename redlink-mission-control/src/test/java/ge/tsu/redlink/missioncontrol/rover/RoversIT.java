package ge.tsu.redlink.missioncontrol.rover;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rovers API over HTTP against real PostgreSQL: conditional GET, conditional PATCH, merge
 * patch rules, Problem Details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RoversIT {

    private static final String MERGE_PATCH = "application/merge-patch+json";
    private static final String PROBLEM = "application/problem+json";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;

    private String viewer;
    private String operator;

    @BeforeEach
    void setUp() {
        jdbc.sql("UPDATE rovers SET name = 'Perseverance-sim' WHERE id = 1").update();
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR");
    }

    // --- reading ---------------------------------------------------------------------------------

    @Test
    void listIsPagedWithAStableShape() throws Exception {
        mvc.perform(get("/api/v1/rovers").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].lastKnown.reportedAt").doesNotExist())
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void pageSizeIsLimited() throws Exception {
        mvc.perform(get("/api/v1/rovers?size=101").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/validation-error"))
                .andExpect(jsonPath("$.errors[0]").value("size: must be less than or equal to 100"));
    }

    @Test
    void roverCarriesAnETagAndMustBeRevalidated() throws Exception {
        mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, currentETag()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(jsonPath("$.name").value("Perseverance-sim"))
                .andExpect(jsonPath("$.siteId").value("jezero-sim"))
                .andExpect(jsonPath("$.lastKnown.x").value(32))
                .andExpect(jsonPath("$.lastKnown.mode").value("NOMINAL"));
    }

    @Test
    void unchangedRoverIs304WithoutBody() throws Exception {
        String etag = currentETag();

        mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, etag))
                .andExpect(content().string(""));
    }

    @Test
    void changedRoverIsSentAgain() throws Exception {
        String old = currentETag();
        rename(old, "Percy").andExpect(status().isOk());

        mvc.perform(get("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_NONE_MATCH, old))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Percy"));
    }

    @Test
    void unknownRoverIs404Problem() throws Exception {
        mvc.perform(get("/api/v1/rovers/999").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(PROBLEM))
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rover-not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/rovers/999"));
    }

    @Test
    void nonNumericIdIs400Problem() throws Exception {
        mvc.perform(get("/api/v1/rovers/abc").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/malformed-request"));
    }

    // --- conditional PATCH ---------------------------------------------------------------------

    @Test
    void patchWithCurrentETagRenamesAndReturnsTheNewETag() throws Exception {
        String old = currentETag();

        String newTag = rename(old, "  Percy  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Percy"))
                .andExpect(header().string(HttpHeaders.ETAG, not(old)))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(newTag).isEqualTo(currentETag());
    }

    @Test
    void patchWithoutIfMatchIs428() throws Exception {
        mvc.perform(patchRequest().content("{\"name\":\"Percy\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/precondition-required"));
        assertThat(name()).isEqualTo("Perseverance-sim");
    }

    @Test
    void patchWithStaleETagIs412AndChangesNothing() throws Exception {
        String old = currentETag();
        rename(old, "Percy").andExpect(status().isOk());

        rename(old, "Overwritten")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/precondition-failed"));
        assertThat(name()).isEqualTo("Percy");
    }

    @Test
    void ifMatchStarMatchesAnyVersion() throws Exception {
        rename("*", "Percy").andExpect(status().isOk());
        assertThat(name()).isEqualTo("Percy");
    }

    @Test
    void weakETagNeverSatisfiesIfMatch() throws Exception {
        rename("W/" + currentETag(), "Percy").andExpect(status().isPreconditionFailed());
    }

    @Test
    void concurrentPatchesWithTheSameETagCannotBothWin() throws Exception {
        String etag = currentETag();
        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            for (int i = 0; i < writers; i++) {
                String name = "Writer " + i;
                Callable<Integer> write = () -> {
                    start.await();
                    return rename(etag, name).andReturn().getResponse().getStatus();
                };
                statuses.add(pool.submit(write));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnly(200, 412).filteredOn(s -> s == 200).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- merge patch rules ---------------------------------------------------------------------

    @Test
    void plainJsonIsNotAMergePatch() throws Exception {
        mvc.perform(patch("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, operator)
                        .header(HttpHeaders.IF_MATCH, currentETag())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Percy\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(PROBLEM));
    }

    @Test
    void onlyTheNameCanBeChanged() throws Exception {
        mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, currentETag())
                        .content("{\"name\":\"Percy\",\"lastKnown\":{\"x\":0},\"id\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/validation-error"))
                .andExpect(jsonPath("$.errors", contains("id: cannot be changed", "lastKnown: cannot be changed")));
        assertThat(name()).isEqualTo("Perseverance-sim");
    }

    @Test
    void nameCannotBeRemovedOrBlank() throws Exception {
        mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, currentETag()).content("{\"name\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("name: cannot be removed"));
        mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, currentETag()).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("name: must not be blank"));
        mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, currentETag()).content("{\"name\":\"" + "x".repeat(65) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, currentETag()).content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/malformed-request"));
    }

    @Test
    void viewerCannotPatch() throws Exception {
        mvc.perform(patch("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, viewer)
                        .header(HttpHeaders.IF_MATCH, currentETag())
                        .contentType(MERGE_PATCH).content("{\"name\":\"Percy\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://redlink.example/problems/forbidden"));
    }

    // --- helpers -------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder patchRequest() {
        return patch("/api/v1/rovers/1").header(HttpHeaders.AUTHORIZATION, operator).contentType(MERGE_PATCH);
    }

    private org.springframework.test.web.servlet.ResultActions rename(String ifMatch, String name) throws Exception {
        return mvc.perform(patchRequest().header(HttpHeaders.IF_MATCH, ifMatch).content("{\"name\":\"" + name + "\"}"));
    }

    private String currentETag() {
        return "\"" + jdbc.sql("SELECT version FROM rovers WHERE id = 1").query(Long.class).single() + "\"";
    }

    private String name() {
        return jdbc.sql("SELECT name FROM rovers WHERE id = 1").query(String.class).single();
    }
}
