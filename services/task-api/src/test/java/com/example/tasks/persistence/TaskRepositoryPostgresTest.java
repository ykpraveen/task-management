package com.example.tasks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Task-core schema against real PostgreSQL, set up exactly as in Compose: the privileged
 * {@code bootstrap.sql} (roles, schemas, default privileges), the identity migrations, then this
 * service's Flyway migrations as {@code task_owner}. Assertions run as the runtime role
 * {@code task_app} unless they inspect the catalog.
 *
 * <p>Schema contract for the task-core migration (T035): tables {@code task.task},
 * {@code task.deadline_request}, {@code task.activity_event} and {@code task.mutation_receipt}
 * with the columns named in data-model.md.
 */
@Testcontainers
class TaskRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("../../infra/database/bootstrap/bootstrap.sql"), "/bootstrap.sql");

    static final String OWNER_PASSWORD = "task-owner-test";
    static final String APP_PASSWORD = "task-app-test";

    static JdbcTemplate admin;
    static JdbcTemplate app;
    static DriverManagerDataSource appDataSource;
    static TransactionTemplate appTx;
    static ExecutorService pool;

    static UUID creator;
    static UUID assignee;

    @BeforeAll
    static void migrate() throws Exception {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute(Files.readString(Path.of("src/test/resources/db/supabase-stub.sql")));

        ExecResult bootstrap = POSTGRES.execInContainer("psql", "-q", "-v", "ON_ERROR_STOP=1",
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "identity_owner_password=identity-owner-test",
                "-v", "task_owner_password=" + OWNER_PASSWORD,
                "-v", "task_app_password=" + APP_PASSWORD,
                "-v", "notification_owner_password=notification-owner-test",
                "-v", "notification_app_password=notification-app-test",
                "-f", "/bootstrap.sql");
        assertThat(bootstrap.getExitCode()).as("bootstrap.sql: %s", bootstrap.getStderr()).isZero();

        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../../infra/identity/migrations")
                .schemas("identity").createSchemas(false).table("bootstrap_schema_history")
                .load().migrate();
        admin.update("insert into identity.settings (default_time_zone) values ('Europe/Berlin')");

        Flyway.configure()
                .dataSource(url, "task_owner", OWNER_PASSWORD)
                .locations("filesystem:src/main/resources/db/migration")
                .schemas("task").defaultSchema("task").createSchemas(false).table("flyway_schema_history")
                .outOfOrder(false)
                .load().migrate();

        appDataSource = new DriverManagerDataSource(url, "task_app", APP_PASSWORD);
        app = new JdbcTemplate(appDataSource);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(appDataSource));
        pool = Executors.newFixedThreadPool(8);

        creator = directoryUser("creator@demo.invalid");
        assignee = directoryUser("assignee@demo.invalid");
    }

    @AfterAll
    static void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    @BeforeEach
    void clean() {
        admin.execute("truncate task.mutation_receipt, task.activity_event, task.deadline_request, task.task cascade");
    }

    // ------------------------------------------------------------------ task constraints

    @Nested
    class TaskConstraints {

        @Test
        void acceptsAValidOpenTask() {
            long id = insertTask("Prepare the quarterly report", "todo");

            Map<String, Object> row = app.queryForMap("select * from task.task where id = ?", id);
            assertThat(row).containsEntry("title", "Prepare the quarterly report")
                    .containsEntry("status", "todo")
                    .containsEntry("priority", "normal")
                    .containsEntry("creator_id", creator)
                    .containsEntry("assignee_id", assignee);
            assertThat(((Number) row.get("version")).longValue()).isPositive();
            assertThat(row.get("created_at")).isNotNull();
            assertThat(row.get("due_at")).isNull();
            assertThat(row.get("completed_at")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", " leading space", "trailing space ", "\ttab"})
        void titleMustBeTrimmedAndNonEmpty(String title) {
            assertRejected(() -> insertTask(title, "todo"));
        }

        @Test
        void titleIsAtMost200Characters() {
            insertTask("t".repeat(200), "todo");
            assertRejected(() -> insertTask("t".repeat(201), "todo"));
        }

        @Test
        void descriptionIsAtMost20000Characters() {
            insertTask("Long description", "todo", Map.of("description", "d".repeat(20_000)));
            assertRejected(() -> insertTask("Too long", "todo", Map.of("description", "d".repeat(20_001))));
        }

        @Test
        void priorityAndStatusAreClosedSets() {
            for (String priority : List.of("low", "normal", "high")) {
                insertTask("Priority " + priority, "todo", Map.of("priority", priority));
            }
            assertRejected(() -> insertTask("Urgent", "todo", Map.of("priority", "urgent")));
            assertRejected(() -> insertTask("Archived", "archived"));
        }

        @Test
        void deadlineAndZoneAreBothPresentOrBothAbsent() {
            Timestamp due = Timestamp.from(Instant.parse("2026-12-01T22:59:00Z"));
            insertTask("Dated", "todo", Map.of("due_at", due, "due_time_zone", "Europe/Berlin"));

            assertRejected(() -> insertTask("No zone", "todo", Map.of("due_at", due)));
            assertRejected(() -> insertTask("No instant", "todo", Map.of("due_time_zone", "Europe/Berlin")));
        }

        @Test
        void blockedRequiresAReasonOf1To2000Characters() {
            insertTask("Blocked", "blocked", Map.of("blocked_reason", "r".repeat(2_000)));

            assertRejected(() -> insertTask("No reason", "blocked"));
            assertRejected(() -> insertTask("Empty reason", "blocked", Map.of("blocked_reason", "")));
            assertRejected(() -> insertTask("Long reason", "blocked", Map.of("blocked_reason", "r".repeat(2_001))));
            assertRejected(() -> insertTask("Not blocked", "todo", Map.of("blocked_reason", "stale reason")));
        }

        @Test
        void cancelledRequiresAReasonOf1To2000Characters() {
            insertTask("Cancelled", "cancelled", Map.of("cancellation_reason", "No longer needed"));

            assertRejected(() -> insertTask("No reason", "cancelled"));
            assertRejected(() -> insertTask("Long reason", "cancelled", Map.of("cancellation_reason", "r".repeat(2_001))));
            assertRejected(() -> insertTask("Open", "in_progress", Map.of("cancellation_reason", "stale reason")));
        }

        @Test
        void onlyDoneHasACompletionTime() {
            Timestamp now = Timestamp.from(Instant.now());
            insertTask("Done", "done", Map.of("completed_at", now));

            assertRejected(() -> insertTask("Done without time", "done"));
            assertRejected(() -> insertTask("Open with time", "todo", Map.of("completed_at", now)));
        }

        @Test
        void creatorIsImmutable() {
            long id = insertTask("Owned", "todo");

            assertRejected(() -> app.update("update task.task set creator_id = ? where id = ?", assignee, id));
            assertThat(app.queryForObject("select creator_id from task.task where id = ?", UUID.class, id)).isEqualTo(creator);
        }

        @Test
        void versionOnlyIncreases() {
            long id = insertTask("Versioned", "todo");
            long version = version(id);

            assertThat(app.update("update task.task set title = 'Renamed', version = version + 1 where id = ?", id)).isOne();
            assertThat(version(id)).isEqualTo(version + 1);

            assertRejected(() -> app.update("update task.task set title = 'Same version' where id = ?", id));
            assertRejected(() -> app.update("update task.task set version = version - 1 where id = ?", id));
            assertThat(version(id)).isEqualTo(version + 1);
        }

        @Test
        void exposesIndexesForParticipantListsAndStableOrdering() {
            List<String> indexes = admin.queryForList(
                    "select indexdef from pg_indexes where schemaname = 'task' and tablename = 'task'", String.class);

            assertThat(indexes).anySatisfy(def -> assertThat(def).contains("creator_id").containsAnyOf("due_at", "status"));
            assertThat(indexes).anySatisfy(def -> assertThat(def).contains("assignee_id").containsAnyOf("due_at", "status"));
            assertThat(indexes).anySatisfy(def -> assertThat(def).containsPattern("\\(created_at, id\\)"));
        }
    }

    // ------------------------------------------------------------------ identity-generated IDs

    @Nested
    class GeneratedIds {

        @Test
        void returningAndJdbcGeneratedKeysYieldTheNewId() {
            Long returned = app.queryForObject("""
                    insert into task.task (creator_id, assignee_id, title, description, priority, status)
                    values (?, ?, 'Returning', '', 'normal', 'todo') returning id
                    """, Long.class, creator, assignee);

            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            app.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        insert into task.task (creator_id, assignee_id, title, description, priority, status)
                        values (?, ?, 'Generated key', '', 'normal', 'todo')
                        """, new String[] {"id"});
                ps.setObject(1, creator);
                ps.setObject(2, assignee);
                return ps;
            }, keys);
            Long generated = keys.getKeyAs(Long.class);

            assertThat(returned).isPositive();
            assertThat(generated).isGreaterThan(returned);
            assertThat(app.queryForObject("select title from task.task where id = ?", String.class, generated))
                    .isEqualTo("Generated key");
        }

        @Test
        void idsAreGeneratedAlwaysAndCannotBeSupplied() {
            assertRejected(() -> app.update("""
                    insert into task.task (id, creator_id, assignee_id, title, description, priority, status)
                    values (42, ?, ?, 'Chosen id', '', 'normal', 'todo')
                    """, creator, assignee));
        }

        @Test
        void eachTableOwnsANonCyclingBigintSequence() {
            List<Map<String, Object>> columns = admin.queryForList("""
                    select c.table_name, c.data_type, c.is_identity, c.identity_generation, c.identity_cycle
                    from information_schema.columns c
                    where c.table_schema = 'task' and c.column_name = 'id'
                      and c.table_name in ('task', 'deadline_request', 'activity_event')
                    """);

            assertThat(columns).hasSize(3).allSatisfy(column -> assertThat(column)
                    .containsEntry("data_type", "bigint")
                    .containsEntry("is_identity", "YES")
                    .containsEntry("identity_generation", "ALWAYS")
                    .containsEntry("identity_cycle", "NO"));

            Set<String> sequences = new HashSet<>(admin.queryForList("""
                    select pg_get_serial_sequence('task.' || t, 'id')
                    from unnest(array['task', 'deadline_request', 'activity_event']) t
                    """, String.class));
            assertThat(sequences).hasSize(3).doesNotContainNull();
        }

        @Test
        void rolledBackInsertsLeaveGapsThatAreNeverReused() {
            long before = insertTask("Before", "todo");

            Long rolledBack = appTx.execute(status -> {
                Long id = insertTaskReturning("Rolled back");
                status.setRollbackOnly();
                return id;
            });
            long after = insertTask("After", "todo");

            assertThat(rolledBack).isGreaterThan(before);
            assertThat(after).isGreaterThan(rolledBack);
            assertThat(app.queryForObject("select count(*) from task.task where id = ?", Long.class, rolledBack)).isZero();
        }

        @Test
        void concurrentInsertsGetDistinctIds() throws Exception {
            List<Callable<Long>> inserts = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                int n = i;
                inserts.add(() -> insertTaskReturning("Concurrent " + n));
            }
            Set<Long> ids = new HashSet<>();
            for (Future<Long> future : pool.invokeAll(inserts, 60, TimeUnit.SECONDS)) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(64);
        }

        @Test
        void runtimeRoleCannotReseedSequences() {
            insertTask("Seed", "todo");

            assertThatThrownBy(() -> app.queryForObject(
                    "select setval(pg_get_serial_sequence('task.task', 'id'), 1)", Long.class))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
            assertThatThrownBy(() -> app.execute("alter table task.task alter column id restart with 1"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
        }

        @Test
        void tiesOnCreatedAtAreOrderedById() {
            Timestamp same = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            List<Long> inserted = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                inserted.add(insertTask("Tie " + i, "todo", Map.of("created_at", same)));
            }
            List<Long> ordered = app.queryForList(
                    "select id from task.task where created_at = ? order by created_at, id", Long.class, same);
            assertThat(ordered).containsExactlyElementsOf(inserted.stream().sorted().toList());
        }
    }

    // ------------------------------------------------------------------ versions and locking

    @Nested
    class Concurrency {

        @Test
        void staleVersionUpdatesAffectNoRows() {
            long id = insertTask("Optimistic", "todo");
            long version = version(id);

            assertThat(app.update("""
                    update task.task set title = 'First', version = version + 1 where id = ? and version = ?
                    """, id, version)).isOne();
            assertThat(app.update("""
                    update task.task set title = 'Second', version = version + 1 where id = ? and version = ?
                    """, id, version)).isZero();

            assertThat(app.queryForObject("select title from task.task where id = ?", String.class, id)).isEqualTo("First");
        }

        @Test
        void taskRowLockSerializesConcurrentMutations() throws Exception {
            long id = insertTask("Locked", "todo");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Future<?> first = pool.submit(() -> appTx.executeWithoutResult(status -> {
                app.queryForObject("select version from task.task where id = ? for update", Long.class, id);
                locked.countDown();
                await(release);
                app.update("update task.task set status = 'in_progress', version = version + 1 where id = ?", id);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<String> second = pool.submit(() -> appTx.execute(status -> {
                app.queryForObject("select version from task.task where id = ? for update", Long.class, id);
                return app.queryForObject("select status from task.task where id = ?", String.class, id);
            }));

            Thread.sleep(500);
            assertThat(second.isDone()).as("second mutation waits for the task lock").isFalse();

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(second.get(10, TimeUnit.SECONDS)).as("second mutation sees the committed change")
                    .isEqualTo("in_progress");
        }

        @Test
        void lockTimeoutSurfacesInsteadOfWaitingForever() throws Exception {
            long id = insertTask("Held", "todo");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            Future<?> holder = pool.submit(() -> appTx.executeWithoutResult(status -> {
                app.queryForObject("select id from task.task where id = ? for update", Long.class, id);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Throwable waited = catchThrowable(() -> appTx.executeWithoutResult(status -> {
                app.execute("set local lock_timeout = '200ms'");
                app.queryForObject("select id from task.task where id = ? for update", Long.class, id);
            }));
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(sqlState(waited)).isEqualTo("55P03");
        }
    }

    // ------------------------------------------------------------------ deadline requests

    @Nested
    class DeadlineRequests {

        @Test
        void onlyOnePendingRequestPerTask() {
            long task = insertTask("Request target", "in_progress");
            long first = insertRequest(task, "pending");

            assertThatThrownBy(() -> insertRequest(task, "pending"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));

            app.update("update task.deadline_request set state = 'withdrawn', resolved_at = now(), "
                    + "resolved_by = ?, version = version + 1 where id = ?", assignee, first);
            assertThat(insertRequest(task, "pending")).isGreaterThan(first);
        }

        @Test
        void resolvedRequestsMayAccumulate() {
            long task = insertTask("History", "in_progress");
            for (String state : List.of("approved", "declined", "withdrawn", "superseded")) {
                insertRequest(task, state);
            }
            assertThat(app.queryForObject("select count(*) from task.deadline_request where task_id = ?", Long.class, task))
                    .isEqualTo(4);
        }

        @Test
        void stateIsAClosedSet() {
            long task = insertTask("States", "in_progress");
            assertRejected(() -> insertRequest(task, "cancelled"));
        }

        @Test
        void requestReferencesAnExistingTask() {
            assertRejected(() -> insertRequest(9_223_372_036_854_775_000L, "pending"));
        }
    }

    // ------------------------------------------------------------------ activity events

    @Nested
    class ActivityEvents {

        @Test
        void eventsAreAppendOnlyForTheRuntimeRole() {
            long task = insertTask("Audited", "todo");
            Long event = app.queryForObject("""
                    insert into task.activity_event (task_id, actor_id, type, changes)
                    values (?, ?, 'task_created', '{"title":{"new":"Audited"}}'::jsonb) returning id
                    """, Long.class, task, creator);

            assertRejected(() -> app.update("update task.activity_event set type = 'edited' where id = ?", event));
            assertRejected(() -> app.update("delete from task.activity_event where id = ?", event));
            assertThat(app.queryForObject("select type from task.activity_event where id = ?", String.class, event))
                    .isEqualTo("task_created");
        }
    }

    // ------------------------------------------------------------------ mutation receipts

    @Nested
    class MutationReceipts {

        @Test
        void keyIsUniquePerActorAndOperation() {
            UUID key = UUID.randomUUID();
            insertReceipt(creator, "create_task", key, Retention.EXACT);

            assertThatThrownBy(() -> insertReceipt(creator, "create_task", key, Retention.EXACT))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));

            // The same key is independent for another actor or operation.
            insertReceipt(assignee, "create_task", key, Retention.EXACT);
            insertReceipt(creator, "change_status", key, Retention.EXACT);
        }

        @Test
        void receiptsAreRetainedAtLeastSevenDays() {
            insertReceipt(creator, "create_task", UUID.randomUUID(), Retention.LONGER);
            assertRejected(() -> insertReceipt(creator, "create_task", UUID.randomUUID(), Retention.SHORTER));
        }
    }

    // ------------------------------------------------------------------ runtime-role DDL

    @Nested
    class RuntimeRole {

        @ParameterizedTest
        @ValueSource(strings = {
            "create table task.rogue (id bigint)",
            "alter table task.task add column rogue text",
            "drop table task.activity_event",
            "truncate task.task",
            "create index rogue_idx on task.task (title)",
            "create schema rogue",
            "create table public.rogue (id bigint)",
            "alter table task.task disable trigger all",
        })
        void cannotRunDdlOrChangePrivileges(String statement) {
            assertThatThrownBy(() -> app.execute(statement))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
        }

        @Test
        void cannotGrantItsPrivilegesToOthers() {
            // A non-owner without grant option gets a warning, not an error: check the outcome.
            catchThrowable(() -> app.execute("grant select on task.task to anon"));
            assertThat(admin.queryForObject("select has_table_privilege('anon', 'task.task', 'select')", Boolean.class))
                    .isFalse();
        }

        @Test
        void isNeitherOwnerNorSuperuser() {
            Map<String, Object> role = admin.queryForMap(
                    "select rolsuper, rolcreaterole, rolcreatedb, rolbypassrls from pg_roles where rolname = 'task_app'");
            assertThat(role).containsEntry("rolsuper", false).containsEntry("rolcreaterole", false)
                    .containsEntry("rolcreatedb", false).containsEntry("rolbypassrls", false);

            List<String> owners = admin.queryForList(
                    "select distinct tableowner from pg_tables where schemaname = 'task'", String.class);
            assertThat(owners).containsExactly("task_owner");
        }

        @Test
        void cannotReadOtherServiceSchemasOrSupabaseAuth() {
            assertThatThrownBy(() -> app.queryForList("select id from auth.users"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
            assertThatThrownBy(() -> app.execute("create table notification.rogue (id bigint)"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("42501"));
        }
    }

    // ------------------------------------------------------------------ helpers

    enum Retention {
        SHORTER("6 days 23 hours"),
        EXACT("7 days"),
        LONGER("30 days");

        final String interval;

        Retention(String interval) {
            this.interval = interval;
        }
    }

    static UUID directoryUser(String email) {
        UUID id = UUID.randomUUID();
        admin.update("insert into auth.users (id, email, email_confirmed_at) values (?, ?, now())", id, email);
        assertThat(admin.queryForObject("select active from identity.directory_user where user_id = ?", Boolean.class, id))
                .isTrue();
        return id;
    }

    static long insertTask(String title, String status) {
        return insertTask(title, status, Map.of());
    }

    /** Inserts as the runtime role with defaults for omitted columns; returns the generated id. */
    static long insertTask(String title, String status, Map<String, Object> extra) {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("creator_id", creator);
        columns.put("assignee_id", assignee);
        columns.put("title", title);
        columns.put("description", "");
        columns.put("priority", "normal");
        columns.put("status", status);
        columns.putAll(extra);
        String names = String.join(", ", columns.keySet());
        String marks = String.join(", ", columns.keySet().stream().map(c -> "?").toList());
        return app.queryForObject("insert into task.task (" + names + ") values (" + marks + ") returning id",
                Long.class, columns.values().toArray());
    }

    static Long insertTaskReturning(String title) {
        return insertTask(title, "todo");
    }

    static long insertRequest(long taskId, String state) {
        boolean resolved = !"pending".equals(state);
        return app.queryForObject("""
                insert into task.deadline_request
                    (task_id, requester_id, proposed_due_at, proposed_time_zone, reason, state, resolved_at, resolved_by)
                values (?, ?, now() + interval '7 days', 'America/New_York', 'Waiting on supplier', ?, ?, ?)
                returning id
                """, Long.class, taskId, assignee, state,
                resolved ? Timestamp.from(Instant.now()) : null, resolved ? creator : null);
    }

    static void insertReceipt(UUID actor, String operation, UUID key, Retention retention) {
        app.update("""
                insert into task.mutation_receipt
                    (actor_id, operation, idempotency_key, request_hash, response_status, response_body, created_at, expires_at)
                values (?, ?, ?, 'sha256:test', 201, '{}'::jsonb, now(), now() + ?::interval)
                """, actor, operation, key, retention.interval);
    }

    static long version(long taskId) {
        return app.queryForObject("select version from task.task where id = ?", Long.class, taskId);
    }

    /** Integrity (class 23), data (class 22), privilege (42501) or trigger-raised (P0001) rejection. */
    static void assertRejected(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("expected the database to reject the statement").isInstanceOf(DataAccessException.class);
        String state = sqlState(thrown);
        assertThat(state).as("SQLSTATE of %s", thrown.getMessage()).isNotNull()
                .satisfiesAnyOf(
                        s -> assertThat(s).startsWith("23"),
                        s -> assertThat(s).startsWith("22"),
                        s -> assertThat(s).isEqualTo("42501"),
                        s -> assertThat(s).isEqualTo("428C9"),
                        s -> assertThat(s).isEqualTo("P0001"));
    }

    static String sqlState(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
