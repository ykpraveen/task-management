package com.example.tasks.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The public task API end to end against real PostgreSQL, set up as in Compose (bootstrap,
 * identity migrations, task migrations as {@code task_owner}) with the application connected as
 * the runtime role {@code task_app}. Only token signature validation is stubbed: the subject of
 * {@code Bearer user.<uuid>} is accepted, and the per-request active check reads the real
 * identity directory, so deactivation through {@code auth.users} takes effect immediately.
 *
 * <p>Covers contracts/http-api.md for US1: creator and assignee access, outsider 404 versus
 * disallowed-operation 403, inactive users with valid JWTs, idempotency replay and conflict,
 * ETag/If-Match, field errors, and guessed neighbouring or out-of-range IDs.
 */
@SpringBootTest(properties = {
        "management.server.port=",
        "internal.callers.notifications.token=notifications-token-0123456789",
        "internal.callers.operator.token=operator-token-0123456789"})
@AutoConfigureMockMvc
class TaskApiIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/database/bootstrap/bootstrap.sql"), "/bootstrap.sql");

    static final String APP_PASSWORD = "task-app-test";
    static final String JDBC_URL;
    static final JdbcTemplate ADMIN;

    static {
        POSTGRES.start();
        JDBC_URL = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified";
        ADMIN = new JdbcTemplate(new DriverManagerDataSource(JDBC_URL, POSTGRES.getUsername(), POSTGRES.getPassword()));
        migrate();
    }

    static final UUID CREATOR = directoryUser("casey.creator@demo.invalid", "Casey Creator", "Europe/Berlin");
    static final UUID ASSIGNEE = directoryUser("avery.assignee@demo.invalid", "Avery Assignee", "America/New_York");
    static final UUID REPLACEMENT = directoryUser("riley.replacement@demo.invalid", "Riley Replacement", "Asia/Tokyo");
    static final UUID OUTSIDER = directoryUser("olive.outsider@demo.invalid", "Olive Outsider", "Europe/Berlin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "task_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("tasks.default-time-zone", () -> "Europe/Berlin");
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        ADMIN.execute("truncate task.mutation_receipt, task.activity_event, task.deadline_request, task.task cascade");
        ADMIN.update("update auth.users set banned_until = null, deleted_at = null");
        when(jwtDecoder.decode(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if (!token.startsWith("user.")) {
                throw new BadJwtException("invalid");
            }
            return Jwt.withTokenValue(token).header("alg", "ES256").subject(token.substring("user.".length()))
                    .claim("role", "authenticated").audience(java.util.List.of("authenticated"))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        });
    }

    // ------------------------------------------------------------------ creation and participant access

    @Nested
    class Access {

        @Test
        void creatorCreatesATaskWithLocationEtagAndDecimalStringIds() throws Exception {
            MvcResult created = mvc.perform(as(CREATOR, post("/api/v1/tasks"))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"  Prepare the quarterly report ","assigneeId":"%s",
                                     "description":"Numbers for Q4","priority":"high"}
                                    """.formatted(ASSIGNEE)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isString())
                    .andExpect(jsonPath("$.title").value("Prepare the quarterly report"))
                    .andExpect(jsonPath("$.status").value("todo"))
                    .andExpect(jsonPath("$.priority").value("high"))
                    .andExpect(jsonPath("$.creator.id").value(CREATOR.toString()))
                    .andExpect(jsonPath("$.creator.displayName").value("Casey Creator"))
                    .andExpect(jsonPath("$.assignee.email").value("avery.assignee@demo.invalid"))
                    .andExpect(jsonPath("$.assignee.active").doesNotExist())
                    .andExpect(jsonPath("$.assignee.timeZone").doesNotExist())
                    .andExpect(jsonPath("$.dueAt").value(nullValue()))
                    .andExpect(jsonPath("$.dueTimeZone").value(nullValue()))
                    .andExpect(jsonPath("$.isOverdue").value(false))
                    .andExpect(jsonPath("$.version").value("1"))
                    .andExpect(jsonPath("$.permissions.edit").value(true))
                    .andExpect(jsonPath("$.permissions.cancel").value(true))
                    .andExpect(jsonPath("$.permissions.requestDeadline").value(false))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                    .andReturn();

            String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
            assertThat(id).matches("[1-9][0-9]*");
            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/api/v1/tasks/" + id);
        }

        @Test
        void bothParticipantsReadTheTaskWithTheirOwnPermissions() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Shared");

            mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id)))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                    .andExpect(jsonPath("$.permissions.edit").value(true))
                    .andExpect(jsonPath("$.permissions.decideDeadlineRequest").value(true));
            mvc.perform(as(ASSIGNEE, get("/api/v1/tasks/" + id)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Shared"))
                    .andExpect(jsonPath("$.permissions.edit").value(false))
                    .andExpect(jsonPath("$.permissions.cancel").value(false))
                    .andExpect(jsonPath("$.permissions.changeStatus").value(true))
                    .andExpect(jsonPath("$.permissions.requestDeadline").value(true));
        }

        @Test
        void outsidersGet404ForEveryTaskOperation() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Private");

            expectNotFound(mvc.perform(as(OUTSIDER, get("/api/v1/tasks/" + id))));
            expectNotFound(mvc.perform(mutate(OUTSIDER, patch("/api/v1/tasks/" + id), "1", "{\"title\":\"Mine\"}")));
            expectNotFound(mvc.perform(mutate(OUTSIDER, post("/api/v1/tasks/" + id + "/status"), "1", "{\"status\":\"done\"}")));
            expectNotFound(mvc.perform(mutate(OUTSIDER, post("/api/v1/tasks/" + id + "/cancel"), "1", "{\"reason\":\"x\"}")));
            expectNotFound(mvc.perform(as(OUTSIDER, get("/api/v1/tasks/" + id + "/history"))));
            expectNotFound(mvc.perform(as(OUTSIDER, get("/api/v1/tasks/" + id + "/deadline-requests"))));

            mvc.perform(as(OUTSIDER, get("/api/v1/tasks").param("view", "created")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
            mvc.perform(as(OUTSIDER, get("/api/v1/tasks").param("view", "assigned")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void participantsGet403ForDisallowedOperations() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Creator only");

            expectProblem(mvc.perform(mutate(ASSIGNEE, patch("/api/v1/tasks/" + id), "1", "{\"title\":\"Mine\"}")),
                    403, "FORBIDDEN");
            expectProblem(mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/cancel"), "1", "{\"reason\":\"No\"}")),
                    403, "FORBIDDEN");
            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/deadline-requests"), "1", """
                    {"proposedDueAt":"2026-12-20","proposedTimeZone":"Europe/Berlin","reason":"Not the assignee"}
                    """)), 403, "FORBIDDEN");
            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/reopen"), "1", "{}")), 403, "FORBIDDEN");

            mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id))).andExpect(jsonPath("$.version").value("1"));
        }

        @Test
        void reassignmentRevokesTheFormerAssignee() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Moving");

            mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), "1", "{\"assigneeId\":\"%s\"}".formatted(REPLACEMENT)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.assignee.id").value(REPLACEMENT.toString()))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"2\""));

            expectNotFound(mvc.perform(as(ASSIGNEE, get("/api/v1/tasks/" + id))));
            mvc.perform(as(REPLACEMENT, get("/api/v1/tasks/" + id))).andExpect(status().isOk());
            mvc.perform(as(REPLACEMENT, get("/api/v1/tasks/" + id + "/history")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].type", hasItem("task_created")));
        }

        @Test
        void participantListsShowOnlyTheirOwnTasks() throws Exception {
            String mine = createTask(CREATOR, ASSIGNEE, "Assigned to Avery");
            createTask(CREATOR, REPLACEMENT, "Assigned to Riley");

            mvc.perform(as(ASSIGNEE, get("/api/v1/tasks").param("view", "assigned")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(mine));
            mvc.perform(as(CREATOR, get("/api/v1/tasks").param("view", "created")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        }
    }

    // ------------------------------------------------------------------ authentication and activity

    @Nested
    class InactiveUsers {

        @Test
        void aBannedUserWithAValidJwtIsRejectedOnTheNextRequest() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Before the ban");
            mvc.perform(as(ASSIGNEE, get("/api/v1/tasks/" + id))).andExpect(status().isOk());

            ADMIN.update("update auth.users set banned_until = now() + interval '1 day' where id = ?", ASSIGNEE);

            expectProblem(mvc.perform(as(ASSIGNEE, get("/api/v1/tasks/" + id))), 401, "UNAUTHENTICATED");
            expectProblem(mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1",
                    "{\"status\":\"in_progress\"}")), 401, "UNAUTHENTICATED");
        }

        @Test
        void aDeletedUserWithAValidJwtIsRejected() throws Exception {
            ADMIN.update("update auth.users set deleted_at = now() where id = ?", OUTSIDER);

            expectProblem(mvc.perform(as(OUTSIDER, get("/api/v1/tasks").param("view", "assigned"))), 401, "UNAUTHENTICATED");
        }

        @Test
        void inactiveUsersCannotBeAssignedOrFound() throws Exception {
            ADMIN.update("update auth.users set banned_until = now() + interval '1 day' where id = ?", REPLACEMENT);

            expectFieldError(mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null,
                    "{\"title\":\"For Riley\",\"assigneeId\":\"%s\"}".formatted(REPLACEMENT))), "assigneeId");
            mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "riley")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
        }

        @Test
        void rejectsMissingAndForgedTokens() throws Exception {
            expectProblem(mvc.perform(get("/api/v1/tasks").param("view", "assigned")), 401, "UNAUTHENTICATED");
            expectProblem(mvc.perform(get("/api/v1/tasks").param("view", "assigned")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer forged")), 401, "UNAUTHENTICATED");
        }
    }

    // ------------------------------------------------------------------ user directory search

    @Nested
    class UserSearch {

        @Test
        void findsActiveUsersByNameOrEmailCaseInsensitively() throws Exception {
            mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "  AVERY ")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(ASSIGNEE.toString()))
                    .andExpect(jsonPath("$.items[0].displayName").value("Avery Assignee"))
                    .andExpect(jsonPath("$.items[0].email").value("avery.assignee@demo.invalid"))
                    .andExpect(jsonPath("$.items[0].active").doesNotExist())
                    .andExpect(jsonPath("$.items[0].timeZone").doesNotExist());
            mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "riley.replacement@")))
                    .andExpect(jsonPath("$.items[0].id").value(REPLACEMENT.toString()));
        }

        @Test
        void distinguishesEmptyQueriesFromNoMatches() throws Exception {
            expectFieldError(mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "   "))), "query");
            expectFieldError(mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "q".repeat(201)))), "query");
            mvc.perform(as(CREATOR, get("/api/v1/users").param("query", "nobody-by-this-name")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
        }
    }

    // ------------------------------------------------------------------ idempotency

    @Nested
    class Idempotency {

        @Test
        void replayingTheSameKeyAndBodyReturnsTheOriginalResponseWithoutASecondTask() throws Exception {
            String key = UUID.randomUUID().toString();
            String body = "{\"title\":\"Once only\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE);

            String first = mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, body, key))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String second = mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, body, key))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

            assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
            assertThat(ADMIN.queryForObject("select count(*) from task.task", Long.class)).isOne();
            assertThat(ADMIN.queryForObject("select count(*) from task.activity_event", Long.class)).isOne();
        }

        @Test
        void reusingAKeyWithADifferentBodyIsAConflict() throws Exception {
            String key = UUID.randomUUID().toString();
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null,
                            "{\"title\":\"First\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE), key))
                    .andExpect(status().isCreated());

            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null,
                    "{\"title\":\"Changed\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE), key)),
                    409, "IDEMPOTENCY_CONFLICT");
            assertThat(ADMIN.queryForObject("select count(*) from task.task", Long.class)).isOne();
        }

        @Test
        void keysAreScopedToTheActor() throws Exception {
            String key = UUID.randomUUID().toString();
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null,
                    "{\"title\":\"Casey's\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE), key))
                    .andExpect(status().isCreated());
            mvc.perform(mutate(OUTSIDER, post("/api/v1/tasks"), null,
                    "{\"title\":\"Olive's\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE), key))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Olive's"));
        }

        @Test
        void aReplayAfterAStaleVersionStillReturnsTheReceipt() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Retry me");
            String key = UUID.randomUUID().toString();

            mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), "1", "{\"title\":\"Retried\"}", key))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2"));
            // The lost-response retry carries the original If-Match, which is now stale.
            mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), "1", "{\"title\":\"Retried\"}", key))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value("2"));

            assertThat(ADMIN.queryForObject("select version from task.task where id = ?", Long.class, Long.parseLong(id)))
                    .isEqualTo(2);
        }

        @Test
        void aReplayRechecksCurrentAccessFirst() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Revoked replay");
            String key = UUID.randomUUID().toString();
            String body = "{\"status\":\"in_progress\"}";
            mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1", body, key))
                    .andExpect(status().isOk());
            mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), "2", "{\"assigneeId\":\"%s\"}".formatted(REPLACEMENT)))
                    .andExpect(status().isOk());

            expectNotFound(mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1", body, key)));
        }

        @Test
        void mutationsRequireAUuidKey() throws Exception {
            String body = "{\"title\":\"Keyless\",\"assigneeId\":\"%s\"}".formatted(ASSIGNEE);

            expectProblem(mvc.perform(as(CREATOR, post("/api/v1/tasks")).contentType(MediaType.APPLICATION_JSON).content(body)),
                    428, "PRECONDITION_REQUIRED");
            expectProblem(mvc.perform(as(CREATOR, post("/api/v1/tasks")).header("Idempotency-Key", "not-a-uuid")
                    .contentType(MediaType.APPLICATION_JSON).content(body)), 422, "VALIDATION_FAILED");
            assertThat(ADMIN.queryForObject("select count(*) from task.task", Long.class)).isZero();
        }
    }

    // ------------------------------------------------------------------ ETag / If-Match

    @Nested
    class Versions {

        @Test
        void everyMutationReturnsTheNextVersionAsItsEtag() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Versioned");

            mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1", "{\"status\":\"in_progress\"}"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                    .andExpect(jsonPath("$.status").value("in_progress"));
            mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "2",
                            "{\"status\":\"blocked\",\"reason\":\"Waiting on supplier\"}"))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ETAG, "\"3\""))
                    .andExpect(jsonPath("$.blockedReason").value("Waiting on supplier"));
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/status"), "3", "{\"status\":\"done\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.completedAt").isString())
                    .andExpect(jsonPath("$.blockedReason").value(nullValue()));
            mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/reopen"), "4", "{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("todo"))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"5\""));
        }

        @Test
        void aStaleIfMatchIsAConflictThatReportsTheCurrentVersion() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Contested");
            mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1", "{\"status\":\"in_progress\"}"))
                    .andExpect(status().isOk());

            expectProblem(mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), "1", "{\"title\":\"Late edit\"}")),
                    409, "STALE_VERSION")
                    .andExpect(header().string(HttpHeaders.ETAG, "\"2\""));
            mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id))).andExpect(jsonPath("$.title").value("Contested"));
            assertThat(ADMIN.queryForObject("select count(*) from task.activity_event", Long.class)).isEqualTo(2);
        }

        @Test
        void staleRequestsFromOutsidersRevealNothing() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Hidden version");

            expectNotFound(mvc.perform(mutate(OUTSIDER, patch("/api/v1/tasks/" + id), "99", "{\"title\":\"x\"}")))
                    .andExpect(header().doesNotExist(HttpHeaders.ETAG));
        }

        @Test
        void taskMutationsRequireIfMatch() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Unconditional");

            expectProblem(mvc.perform(mutate(CREATOR, patch("/api/v1/tasks/" + id), null, "{\"title\":\"x\"}")),
                    428, "PRECONDITION_REQUIRED");
            expectProblem(mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), null,
                    "{\"status\":\"done\"}")), 428, "PRECONDITION_REQUIRED");
            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/cancel"), null,
                    "{\"reason\":\"x\"}")), 428, "PRECONDITION_REQUIRED");
        }

        @Test
        void deadlineRequestDecisionsUseTheTaskVersion() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Request flow");

            MvcResult submitted = mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/deadline-requests"), "1", """
                            {"proposedDueAt":"2026-12-20","proposedTimeZone":"America/New_York","reason":"Supplier delay"}
                            """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isString())
                    .andExpect(jsonPath("$.taskId").value(id))
                    .andExpect(jsonPath("$.state").value("pending"))
                    .andExpect(jsonPath("$.requester.id").value(ASSIGNEE.toString()))
                    .andReturn();
            String requestId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/deadline-requests/" + requestId + "/approve"),
                    "1", "{}")), 409, "STALE_VERSION");
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/deadline-requests/" + requestId + "/approve"), "2", "{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.request.state").value("approved"))
                    .andExpect(jsonPath("$.task.dueTimeZone").value("America/New_York"))
                    .andExpect(header().string(HttpHeaders.ETAG, "\"3\""));

            mvc.perform(as(ASSIGNEE, get("/api/v1/tasks/" + id)))
                    .andExpect(jsonPath("$.dueAt").value("2026-12-21T04:59:00Z"))
                    .andExpect(jsonPath("$.dueTimeZone").value("America/New_York"))
                    .andExpect(jsonPath("$.version").value("3"));
        }
    }

    // ------------------------------------------------------------------ field errors

    @Nested
    class FieldErrors {

        @Test
        void reportsEachInvalidFieldWithItsDtoPath() throws Exception {
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, """
                            {"title":"   ","assigneeId":"not-a-uuid","priority":"urgent",
                             "dueTimeZone":"Europe/Berlin"}
                            """))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.requestId").isString())
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("title")))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("assigneeId")))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("priority")))
                    .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("dueAt")))
                    .andExpect(jsonPath("$.fieldErrors[0].code").isString())
                    .andExpect(jsonPath("$.fieldErrors[0].message").isString());
            assertThat(ADMIN.queryForObject("select count(*) from task.task", Long.class)).isZero();
        }

        @Test
        void rejectsDstGapsAndUnknownZones() throws Exception {
            expectFieldError(mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, """
                    {"title":"Gap","assigneeId":"%s","dueAt":"2027-03-28T02:30","dueTimeZone":"Europe/Berlin"}
                    """.formatted(ASSIGNEE))), "dueAt");
            expectFieldError(mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, """
                    {"title":"Zone","assigneeId":"%s","dueAt":"2026-12-01","dueTimeZone":"Mars/Olympus_Mons"}
                    """.formatted(ASSIGNEE))), "dueTimeZone");
        }

        @Test
        void aPastDeadlineNeedsExplicitConfirmation() throws Exception {
            String body = "{\"title\":\"Late\",\"assigneeId\":\"%s\",\"dueAt\":\"2020-01-01\"%s}";

            expectProblem(mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, body.formatted(ASSIGNEE, ""))),
                    422, "PAST_DEADLINE_CONFIRMATION_REQUIRED");
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null,
                            body.formatted(ASSIGNEE, ",\"confirmPastDeadline\":true")))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.isOverdue").value(true))
                    .andExpect(jsonPath("$.dueTimeZone").value("Europe/Berlin"));
        }

        @Test
        void blockedAndCancelledRequireReasons() throws Exception {
            String id = createTask(CREATOR, ASSIGNEE, "Reasons");

            expectFieldError(mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + id + "/status"), "1",
                    "{\"status\":\"blocked\"}")), "reason");
            expectFieldError(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/cancel"), "1",
                    "{\"reason\":\"  \"}")), "reason");
            expectFieldError(mvc.perform(mutate(CREATOR, post("/api/v1/tasks/" + id + "/status"), "1",
                    "{\"status\":\"cancelled\",\"reason\":\"Use cancel\"}")), "status");
        }

        @Test
        void problemsNeverLeakInternals() throws Exception {
            mvc.perform(mutate(CREATOR, post("/api/v1/tasks"), null, "{\"title\": "))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(content().string(not(containsString("Exception"))))
                    .andExpect(content().string(not(containsString("task.task"))))
                    .andExpect(content().string(not(containsString("at com.example"))));
        }
    }

    // ------------------------------------------------------------------ guessed and out-of-range IDs

    @Nested
    class Identifiers {

        @Test
        void guessedNeighbouringIdsBehaveExactlyLikeInaccessibleOnes() throws Exception {
            String before = createTask(OUTSIDER, OUTSIDER, "Olive's earlier task");
            String mine = createTask(CREATOR, ASSIGNEE, "Casey's task");
            String after = createTask(OUTSIDER, OUTSIDER, "Olive's later task");
            long id = Long.parseLong(mine);

            String inaccessible = notFoundBody(get("/api/v1/tasks/" + before));
            String later = notFoundBody(get("/api/v1/tasks/" + after));
            String missing = notFoundBody(get("/api/v1/tasks/" + (Long.parseLong(after) + 1000)));

            assertThat(later).isEqualTo(inaccessible).isEqualTo(missing);
            assertThat(id).isBetween(Long.parseLong(before) + 1, Long.parseLong(after) - 1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"9007199254740992", "9007199254740993", "9223372036854775807"})
        void idsAbove2To53AreParsedExactlyAndStillRequireAccess(String id) throws Exception {
            expectNotFound(mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id))));
            expectNotFound(mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id + "/history"))));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1", "01", "+1", "1e3", "1.0", "9223372036854775808", "99999999999999999999", "abc"})
        void malformedAndOutOfRangeIdsAreValidationErrors(String id) throws Exception {
            expectProblem(mvc.perform(as(CREATOR, get("/api/v1/tasks/" + id))), 422, "VALIDATION_FAILED");
        }

        @Test
        void requestIdsAreCheckedAgainstTheirTask() throws Exception {
            String first = createTask(CREATOR, ASSIGNEE, "First");
            String second = createTask(CREATOR, ASSIGNEE, "Second");
            MvcResult submitted = mvc.perform(mutate(ASSIGNEE, post("/api/v1/tasks/" + first + "/deadline-requests"), "1", """
                            {"proposedDueAt":"2026-12-20","proposedTimeZone":"America/New_York","reason":"Delay"}
                            """))
                    .andExpect(status().isCreated()).andReturn();
            String requestId = JsonPath.read(submitted.getResponse().getContentAsString(), "$.id");

            expectNotFound(mvc.perform(mutate(CREATOR,
                    post("/api/v1/tasks/" + second + "/deadline-requests/" + requestId + "/approve"), "1", "{}")));
            expectNotFound(mvc.perform(mutate(CREATOR,
                    post("/api/v1/tasks/" + first + "/deadline-requests/" + (Long.parseLong(requestId) + 1) + "/approve"),
                    "2", "{}")));
        }
    }

    // ------------------------------------------------------------------ helpers

    static void migrate() {
        try {
            ADMIN.execute(Files.readString(Path.of("src/test/resources/db/supabase-stub.sql")));
            ExecResult bootstrap = POSTGRES.execInContainer("psql", "-q", "-v", "ON_ERROR_STOP=1",
                    "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                    "-v", "identity_owner_password=identity-owner-test",
                    "-v", "task_owner_password=task-owner-test",
                    "-v", "task_app_password=" + APP_PASSWORD,
                    "-v", "notification_owner_password=notification-owner-test",
                    "-v", "notification_app_password=notification-app-test",
                    "-f", "/bootstrap.sql");
            assertThat(bootstrap.getExitCode()).as("bootstrap.sql: %s", bootstrap.getStderr()).isZero();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Flyway.configure()
                .dataSource(JDBC_URL, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../../infra/identity/migrations")
                .schemas("identity").createSchemas(false).table("bootstrap_schema_history")
                .load().migrate();
        ADMIN.update("insert into identity.settings (default_time_zone) values ('Europe/Berlin')");
        Flyway.configure()
                .dataSource(JDBC_URL, "task_owner", "task-owner-test")
                .locations("filesystem:src/main/resources/db/migration")
                .schemas("task").defaultSchema("task").createSchemas(false).table("flyway_schema_history")
                .outOfOrder(false)
                .load().migrate();
    }

    static UUID directoryUser(String email, String displayName, String timeZone) {
        UUID id = UUID.randomUUID();
        ADMIN.update("""
                insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
                values (?, ?, now(), jsonb_build_object('display_name', ?::text, 'time_zone', ?::text))
                """, id, email, displayName, timeZone);
        return id;
    }

    static MockHttpServletRequestBuilder as(UUID user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer user." + user);
    }

    /** A mutating JSON request with a fresh Idempotency-Key and, when given, If-Match on that version. */
    static MockHttpServletRequestBuilder mutate(UUID user, MockHttpServletRequestBuilder request, String version, String body) {
        return mutate(user, request, version, body, UUID.randomUUID().toString());
    }

    /** As {@link #mutate(UUID, MockHttpServletRequestBuilder, String, String)} with a chosen Idempotency-Key. */
    static MockHttpServletRequestBuilder mutate(UUID user, MockHttpServletRequestBuilder request, String version, String body,
            String key) {
        MockHttpServletRequestBuilder builder = as(user, request)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return version == null ? builder : builder.header(HttpHeaders.IF_MATCH, "\"" + version + "\"");
    }

    String createTask(UUID creator, UUID assignee, String title) throws Exception {
        MvcResult result = mvc.perform(mutate(creator, post("/api/v1/tasks"), null,
                        "{\"title\":\"%s\",\"assigneeId\":\"%s\"}".formatted(title, assignee)))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    /** The 404 body as Casey sees it, minus the per-request ID and the echoed request path. */
    String notFoundBody(MockHttpServletRequestBuilder request) throws Exception {
        String body = expectNotFound(mvc.perform(as(CREATOR, request))).andReturn().getResponse().getContentAsString();
        return body.replaceAll("\"(requestId|instance)\"\\s*:\\s*\"[^\"]*\"", "\"$1\":\"\"");
    }

    static ResultActions expectNotFound(ResultActions actions) throws Exception {
        return expectProblem(actions, 404, "NOT_FOUND");
    }

    static ResultActions expectProblem(ResultActions actions, int status, String code) throws Exception {
        return actions.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.requestId").isString());
    }

    static ResultActions expectFieldError(ResultActions actions, String field) throws Exception {
        return expectProblem(actions, 422, "VALIDATION_FAILED")
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem(field)));
    }
}
