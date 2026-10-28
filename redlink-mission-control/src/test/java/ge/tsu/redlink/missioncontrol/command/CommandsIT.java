package ge.tsu.redlink.missioncontrol.command;

import ge.tsu.redlink.missioncontrol.TestTokens;
import ge.tsu.redlink.missioncontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The commands API over HTTP against real PostgreSQL: async 202, Idempotency-Key, HATEOAS links,
 * cancel, Problem Details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CommandsIT {

    private static final String MOVE_5 = "{\"type\":\"MOVE\",\"distanceMeters\":5}";
    private static final String PROBLEM = "application/problem+json";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtEncoder jwtEncoder;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JsonMapper jsonMapper;

    private String viewer;
    private String operator;
    private String director;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM uplink_packets").update();
        jdbc.sql("DELETE FROM idempotency_records").update();
        jdbc.sql("DELETE FROM commands").update();
        jdbc.sql("UPDATE rovers SET last_battery_percent = 100, last_mode = 'NOMINAL' WHERE id = 1").update();
        viewer = TestTokens.bearer(jwtEncoder, "VIEWER");
        operator = TestTokens.bearer(jwtEncoder, "OPERATOR");
        director = TestTokens.bearer(jwtEncoder, "FLIGHT_DIRECTOR");
    }

    @Nested
    class Submitting {

        @Test
        void commandIsAcceptedNotDone() throws Exception {
            MockHttpServletResponse response = submit(operator, UUID.randomUUID().toString(), MOVE_5)
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/commands/")))
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andExpect(jsonPath("$.command.type").value("MOVE"))
                    .andExpect(jsonPath("$.command.distanceMeters").value(5))
                    .andExpect(jsonPath("$.createdBy").value("operator"))
                    .andExpect(jsonPath("$.sequenceNo").isEmpty())
                    .andExpect(jsonPath("$._links.self.href").exists())
                    .andExpect(jsonPath("$._links.rover.href").value("/api/v1/rovers/1"))
                    .andExpect(jsonPath("$._links.cancel.method").value("POST"))
                    .andReturn().getResponse();

            assertThat(response.getHeader(HttpHeaders.LOCATION))
                    .isEqualTo(jsonMapper.readTree(response.getContentAsString()).get("_links").get("self").get("href").asString());
            assertThat(commandCount()).isEqualTo(1);
        }

        @Test
        void drillingWaitsForApproval() throws Exception {
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"DRILL_SAMPLE\"}")
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                    .andExpect(jsonPath("$._links.cancel").exists());
        }

        @Test
        void commandOutsideTheDictionaryIsRejected() throws Exception {
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"MOVE\",\"distanceMeters\":0}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/validation-error"))
                    .andExpect(jsonPath("$.errors[0]").value(startsWith("distanceMeters")));
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"SELF_DESTRUCT\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/malformed-request"));
            assertThat(commandCount()).isZero();
        }

        @Test
        void unknownRoverIs404() throws Exception {
            mvc.perform(post("/api/v1/rovers/999/commands").header(HttpHeaders.AUTHORIZATION, operator)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(MOVE_5))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rover-not-found"));
        }

        @Test
        void viewersCannotSendCommands() throws Exception {
            submit(viewer, UUID.randomUUID().toString(), MOVE_5).andExpect(status().isForbidden());
        }

        @Test
        void roverLastReportedInSafeModeOnlyAcceptsReboot() throws Exception {
            jdbc.sql("UPDATE rovers SET last_mode = 'SAFE_MODE' WHERE id = 1").update();

            submit(operator, UUID.randomUUID().toString(), MOVE_5)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/rover-in-safe-mode"));
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"REBOOT\"}")
                    .andExpect(status().isAccepted());
        }

        @Test
        void drivingFurtherThanTheLastKnownBatteryAllowsIsRejected() throws Exception {
            jdbc.sql("UPDATE rovers SET last_battery_percent = 3 WHERE id = 1").update();

            submit(operator, UUID.randomUUID().toString(), MOVE_5)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentType(PROBLEM))
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/insufficient-battery"))
                    .andExpect(jsonPath("$.detail").value("MOVE 5 needs at least 5% battery, last known level is 3.0%"));
            assertThat(commandCount()).isZero();
        }
    }

    @Nested
    class IdempotencyKey {

        @Test
        void isRequired() throws Exception {
            mvc.perform(post("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, operator)
                            .contentType(MediaType.APPLICATION_JSON).content(MOVE_5))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/invalid-idempotency-key"));
            assertThat(commandCount()).isZero();
        }

        @Test
        void mustBeShortVisibleAscii() throws Exception {
            submit(operator, "x".repeat(256), MOVE_5).andExpect(status().isBadRequest());
            submit(operator, "has space", MOVE_5).andExpect(status().isBadRequest());
        }

        @Test
        void retryReplaysTheOriginalResponseByteForByte() throws Exception {
            String key = UUID.randomUUID().toString();
            MockHttpServletResponse first = submit(operator, key, MOVE_5).andReturn().getResponse();

            MockHttpServletResponse retry = submit(operator, key, MOVE_5)
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(header().string(HttpHeaders.LOCATION, first.getHeader(HttpHeaders.LOCATION)))
                    .andReturn().getResponse();

            assertThat(retry.getContentAsString()).isEqualTo(first.getContentAsString());
            assertThat(commandCount()).isEqualTo(1);
        }

        @Test
        void sameRequestWithDifferentFormattingIsStillTheSameRequest() throws Exception {
            String key = UUID.randomUUID().toString();
            submit(operator, key, MOVE_5).andExpect(status().isAccepted());

            submit(operator, key, "{ \"distanceMeters\" : 5,\n  \"type\" : \"MOVE\" }")
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
            assertThat(commandCount()).isEqualTo(1);
        }

        @Test
        void sameKeyForADifferentRequestIs422() throws Exception {
            String key = UUID.randomUUID().toString();
            submit(operator, key, MOVE_5).andExpect(status().isAccepted());

            submit(operator, key, "{\"type\":\"MOVE\",\"distanceMeters\":6}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/idempotency-key-reused"));
            assertThat(commandCount()).isEqualTo(1);
        }

        @Test
        void keysBelongToTheirUser() throws Exception {
            String key = UUID.randomUUID().toString();
            submit(operator, key, MOVE_5).andExpect(status().isAccepted());

            submit(director, key, MOVE_5)
                    .andExpect(status().isAccepted())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));
            assertThat(commandCount()).isEqualTo(2);
        }

        @Test
        void failedRequestsAreNotRemembered() throws Exception {
            String key = UUID.randomUUID().toString();
            jdbc.sql("UPDATE rovers SET last_battery_percent = 3 WHERE id = 1").update();
            submit(operator, key, MOVE_5).andExpect(status().isUnprocessableContent());

            jdbc.sql("UPDATE rovers SET last_battery_percent = 100 WHERE id = 1").update();
            submit(operator, key, MOVE_5)
                    .andExpect(status().isAccepted())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));
        }

        @Test
        void successIsReplayedEvenAfterTheRoverStateChanged() throws Exception {
            String key = UUID.randomUUID().toString();
            submit(operator, key, MOVE_5).andExpect(status().isAccepted());
            jdbc.sql("UPDATE rovers SET last_mode = 'SAFE_MODE' WHERE id = 1").update();

            submit(operator, key, MOVE_5)
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Idempotent-Replayed", "true"));
        }

        @Test
        void expiredKeyCanBeUsedAgain() throws Exception {
            String key = UUID.randomUUID().toString();
            submit(operator, key, MOVE_5).andExpect(status().isAccepted());
            jdbc.sql("UPDATE idempotency_records SET created_at = now() - interval '25 hours'").update();

            submit(operator, key, "{\"type\":\"MOVE\",\"distanceMeters\":6}")
                    .andExpect(status().isAccepted())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"));
            assertThat(commandCount()).isEqualTo(2);
        }

        @Test
        void concurrentDuplicatesCreateOneCommandAndAllGetTheSameAnswer() throws Exception {
            String key = UUID.randomUUID().toString();
            int clients = 10;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> responses = new ArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(clients);
            try {
                for (int i = 0; i < clients; i++) {
                    Callable<MockHttpServletResponse> call = () -> {
                        start.await();
                        return submit(operator, key, MOVE_5).andReturn().getResponse();
                    };
                    responses.add(pool.submit(call));
                }
                start.countDown();
                Set<String> bodies = new HashSet<>();
                for (Future<MockHttpServletResponse> response : responses) {
                    assertThat(response.get().getStatus()).isEqualTo(202);
                    bodies.add(response.get().getContentAsString());
                }
                assertThat(bodies).hasSize(1);
            } finally {
                pool.shutdownNow();
            }
            assertThat(commandCount()).isEqualTo(1);
        }
    }

    @Nested
    class Reading {

        @Test
        void commandCanBeRevalidated() throws Exception {
            String location = submit(operator, UUID.randomUUID().toString(), MOVE_5).andReturn().getResponse()
                    .getHeader(HttpHeaders.LOCATION);

            String etag = mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

            mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, viewer).header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified());
        }

        @Test
        void unknownCommandIs404() throws Exception {
            mvc.perform(get("/api/v1/commands/999999").header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/command-not-found"));
        }

        @Test
        void roverCommandsAreListedNewestFirstAndFilterable() throws Exception {
            submit(operator, UUID.randomUUID().toString(), MOVE_5);
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"DRILL_SAMPLE\"}");
            submit(operator, UUID.randomUUID().toString(), "{\"type\":\"REBOOT\"}");

            mvc.perform(get("/api/v1/rovers/1/commands").header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].type").value("REBOOT"))
                    .andExpect(jsonPath("$.content[2].type").value("MOVE"))
                    .andExpect(jsonPath("$.page.totalElements").value(3));
            mvc.perform(get("/api/v1/rovers/1/commands?status=PENDING_APPROVAL").header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].type").value("DRILL_SAMPLE"));
            mvc.perform(get("/api/v1/rovers/1/commands?status=SLEEPING").header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Cancelling {

        @Test
        void queuedCommandCanBeCancelledAndTheCancelLinkDisappears() throws Exception {
            String location = submit(operator, UUID.randomUUID().toString(), MOVE_5).andReturn().getResponse()
                    .getHeader(HttpHeaders.LOCATION);

            mvc.perform(post(location + "/cancel").header(HttpHeaders.AUTHORIZATION, operator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                    .andExpect(jsonPath("$._links.cancel").doesNotExist());
        }

        @Test
        void cancellingTwiceIsSafe() throws Exception {
            String location = submit(operator, UUID.randomUUID().toString(), MOVE_5).andReturn().getResponse()
                    .getHeader(HttpHeaders.LOCATION);
            mvc.perform(post(location + "/cancel").header(HttpHeaders.AUTHORIZATION, operator)).andExpect(status().isOk());

            mvc.perform(post(location + "/cancel").header(HttpHeaders.AUTHORIZATION, operator))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        void transmittedCommandCannotBeCalledBack() throws Exception {
            String location = submit(operator, UUID.randomUUID().toString(), MOVE_5).andReturn().getResponse()
                    .getHeader(HttpHeaders.LOCATION);
            jdbc.sql("UPDATE commands SET status = 'TRANSMITTED'").update();

            mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(jsonPath("$._links.cancel").doesNotExist());
            mvc.perform(post(location + "/cancel").header(HttpHeaders.AUTHORIZATION, operator))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://redlink.example/problems/command-not-cancellable"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("on its way to Mars")));
        }

        @Test
        void viewersCannotCancel() throws Exception {
            String location = submit(operator, UUID.randomUUID().toString(), MOVE_5).andReturn().getResponse()
                    .getHeader(HttpHeaders.LOCATION);

            mvc.perform(post(location + "/cancel").header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isForbidden());
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    private ResultActions submit(String token, String idempotencyKey, String body) throws Exception {
        return mvc.perform(post("/api/v1/rovers/1/commands")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private long commandCount() {
        return jdbc.sql("SELECT count(*) FROM commands").query(Long.class).single();
    }
}
