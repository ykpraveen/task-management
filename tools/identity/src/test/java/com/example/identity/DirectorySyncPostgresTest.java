package com.example.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Directory triggers and reconcile against real PostgreSQL with the identity migrations. */
@Testcontainers
class DirectorySyncPostgresTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static JdbcTemplate admin;
    static JdbcTemplate identityOwner;
    static ReconcileCommand reconcile;
    static ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeAll
    static void migrate() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute(Files.readString(Path.of("src/test/resources/auth-stub.sql")));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:../../infra/identity/migrations")
                .schemas("identity")
                .createSchemas(false)
                .table("bootstrap_schema_history")
                .load()
                .migrate();
        admin.update("insert into identity.settings (default_time_zone) values ('Europe/Berlin')");

        DriverManagerDataSource ownerDs = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "identity_owner", "identity_owner");
        identityOwner = new JdbcTemplate(ownerDs);
        reconcile = new ReconcileCommand(identityOwner,
                new TransactionTemplate(new DataSourceTransactionManager(ownerDs)),
                new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    @BeforeEach
    void clean() {
        admin.update("alter table auth.users enable trigger identity_directory_sync");
        admin.update("delete from auth.users");
        admin.update("delete from identity.directory_user");
        output.reset();
    }

    @Test
    void createProjectsConfirmedUserWithMetadata() {
        UUID id = insertUser("ada@demo.invalid", "{\"display_name\":\"Ada\",\"time_zone\":\"Asia/Tokyo\"}", true);

        assertThat(directory(id)).containsEntry("display_name", "Ada")
                .containsEntry("email", "ada@demo.invalid")
                .containsEntry("active", true)
                .containsEntry("time_zone", "Asia/Tokyo")
                .containsEntry("deleted", false);
    }

    @Test
    void createDefaultsNameToEmailLocalPartAndZoneToDefault() {
        UUID id = insertUser("grace.hopper@demo.invalid", null, true);

        assertThat(directory(id)).containsEntry("display_name", "grace.hopper")
                .containsEntry("time_zone", "Europe/Berlin");
    }

    @Test
    void unconfirmedUserIsInactive() {
        UUID id = insertUser("pending@demo.invalid", null, false);

        assertThat(directory(id)).containsEntry("active", false);
    }

    @Test
    void profileChangeUpdatesNameAndZone() {
        UUID id = insertUser("ada@demo.invalid", "{\"display_name\":\"Ada\"}", true);

        admin.update("update auth.users set raw_user_meta_data = '{\"display_name\":\"Ada L.\",\"time_zone\":\"America/New_York\"}' where id = ?", id);

        assertThat(directory(id)).containsEntry("display_name", "Ada L.").containsEntry("time_zone", "America/New_York");
    }

    @Test
    void invalidTimeZoneKeepsPreviousValue() {
        UUID id = insertUser("ada@demo.invalid", "{\"time_zone\":\"Asia/Tokyo\"}", true);

        admin.update("update auth.users set raw_user_meta_data = '{\"time_zone\":\"Mars/Olympus\"}' where id = ?", id);

        assertThat(directory(id)).containsEntry("time_zone", "Asia/Tokyo");
    }

    @Test
    void metadataCannotActivateAnAccount() {
        UUID id = insertUser("pending@demo.invalid", "{\"active\":true,\"banned_until\":null}", false);

        assertThat(directory(id)).containsEntry("active", false);
    }

    @Test
    void banDeactivatesAndUnbanReactivates() {
        UUID id = insertUser("ada@demo.invalid", null, true);

        admin.update("update auth.users set banned_until = now() + interval '1 day' where id = ?", id);
        assertThat(directory(id)).containsEntry("active", false);

        admin.update("update auth.users set banned_until = null where id = ?", id);
        assertThat(directory(id)).containsEntry("active", true);
    }

    @Test
    void expiredBanNoLongerDeactivates() {
        UUID id = insertUser("ada@demo.invalid", null, true);

        admin.update("update auth.users set banned_until = now() - interval '1 minute' where id = ?", id);

        assertThat(directory(id)).containsEntry("active", true);
    }

    @Test
    void deleteIsSoftInProjection() {
        UUID id = insertUser("ada@demo.invalid", "{\"display_name\":\"Ada\"}", true);

        admin.update("delete from auth.users where id = ?", id);

        assertThat(directory(id)).containsEntry("deleted", true)
                .containsEntry("active", false)
                .containsEntry("display_name", "Ada");
    }

    @Test
    void currentUserIsActiveHelperUsesOnlyVerifiedSubject() {
        UUID active = insertUser("ada@demo.invalid", null, true);
        UUID banned = insertUser("bob@demo.invalid", null, true);
        admin.update("update auth.users set banned_until = now() + interval '1 day' where id = ?", banned);

        assertThat(isActiveAs(active)).isTrue();
        assertThat(isActiveAs(banned)).isFalse();
        assertThat(isActiveAs(UUID.randomUUID())).isFalse();
    }

    @Test
    void runtimeRolesReadOnlyTheViewNotTheTable() {
        insertUser("ada@demo.invalid", null, true);
        JdbcTemplate taskApp = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "task_app", "task_app"));

        assertThat(taskApp.queryForObject("select count(*) from identity.directory", Long.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskApp.queryForObject("select count(*) from identity.directory_user", Long.class))
                .rootCause().hasMessageContaining("permission denied");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> taskApp.update("update identity.directory set display_name = 'x'"))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void reconcileCheckReportsNoDriftWhenInSync() {
        insertUser("ada@demo.invalid", "{\"display_name\":\"Ada\"}", true);

        assertThat(reconcile.run(new String[] {"--check"})).isEqualTo(ReconcileCommand.EXIT_OK);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("checked=1 drifted=0 orphaned=0");
    }

    @Test
    void reconcileDetectsAndRepairsMissedSyncIdempotently() {
        UUID kept = insertUser("ada@demo.invalid", "{\"display_name\":\"Ada\"}", true);
        admin.update("alter table auth.users disable trigger identity_directory_sync");
        UUID missed = insertUser("bob@demo.invalid", "{\"display_name\":\"Bob\"}", true);
        admin.update("update auth.users set banned_until = now() + interval '1 day', email = 'ada2@demo.invalid' where id = ?", kept);
        UUID orphan = insertUser("carol@demo.invalid", null, true);
        admin.update("alter table auth.users enable trigger identity_directory_sync");
        admin.update("alter table auth.users disable trigger identity_directory_sync");
        admin.update("delete from auth.users where id = ?", orphan);
        admin.update("insert into identity.directory_user (user_id, display_name, email, active, time_zone) values (?, 'Carol', 'carol@demo.invalid', true, 'UTC')", orphan);

        assertThat(reconcile.run(new String[] {"--check"})).isEqualTo(ReconcileCommand.EXIT_DRIFT);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("drifted=2 orphaned=1");

        assertThat(reconcile.run(new String[] {"--repair"})).isEqualTo(ReconcileCommand.EXIT_OK);
        assertThat(directory(missed)).containsEntry("display_name", "Bob").containsEntry("active", true);
        assertThat(directory(kept)).containsEntry("active", false).containsEntry("email", "ada2@demo.invalid");
        assertThat(directory(orphan)).containsEntry("deleted", true);

        output.reset();
        assertThat(reconcile.run(new String[] {"--repair"})).isEqualTo(ReconcileCommand.EXIT_OK);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("reconcile repair: checked=2 drifted=0 orphaned=0");
    }

    @Test
    void summariesExcludeEmailsNamesAndMetadata() {
        admin.update("alter table auth.users disable trigger identity_directory_sync");
        insertUser("secret.person@demo.invalid", "{\"display_name\":\"Secret Person\",\"token\":\"tok_abc\"}", true);

        reconcile.run(new String[] {"--check"});
        reconcile.run(new String[] {"--repair"});

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("secret.person").doesNotContain("Secret Person").doesNotContain("tok_abc").doesNotContain("@");
    }

    @Test
    void reconcileRejectsUnknownArguments() {
        assertThat(reconcile.run(new String[] {"--fix"})).isEqualTo(ReconcileCommand.EXIT_USAGE);
    }

    private UUID insertUser(String email, String metadata, boolean confirmed) {
        UUID id = UUID.randomUUID();
        admin.update("insert into auth.users (id, email, raw_user_meta_data, email_confirmed_at) values (?, ?, ?::jsonb, ?)",
                id, email, metadata, confirmed ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        return id;
    }

    private Map<String, Object> directory(UUID id) {
        return admin.queryForMap("select display_name, email, active, time_zone, deleted from identity.directory where user_id = ?", id);
    }

    private boolean isActiveAs(UUID subject) {
        return Boolean.TRUE.equals(admin.execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("begin");
                st.execute("set local role authenticated");
                st.execute("select set_config('request.jwt.claim.sub', '" + subject + "', true)");
                try (var rs = st.executeQuery("select identity.current_user_is_active()")) {
                    rs.next();
                    boolean result = rs.getBoolean(1);
                    st.execute("rollback");
                    return result;
                }
            }
        }));
    }
}
