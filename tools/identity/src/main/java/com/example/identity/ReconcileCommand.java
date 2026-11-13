package com.example.identity;

import java.io.PrintStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Compares {@code auth.users} (through {@code identity.auth_users_source()}) with {@code identity.directory_user} and repairs drift.
 * Summaries contain counts only: never emails, names, metadata or tokens.
 */
@Component
public class ReconcileCommand {

    static final int EXIT_OK = 0;
    static final int EXIT_DRIFT = 2;
    static final int EXIT_USAGE = 64;

    /** Auth users whose projection is missing or differs in a server-controlled or valid profile field. */
    private static final String DRIFTED_USERS = """
            select u.id
            from identity.auth_users_source() u
            left join identity.directory_user d on d.user_id = u.id
            where d.user_id is null
               or d.email is distinct from u.email
               or d.active <> ((u.email_confirmed_at is not null or u.phone_confirmed_at is not null)
                               and u.deleted_at is null)
               or d.banned_until is distinct from u.banned_until
               or d.deleted <> (u.deleted_at is not null)
               or (char_length(btrim(coalesce(u.raw_user_meta_data ->> 'display_name', ''))) between 1 and 200
                   and d.display_name <> btrim(u.raw_user_meta_data ->> 'display_name'))
               or (identity.is_valid_time_zone(u.raw_user_meta_data ->> 'time_zone')
                   and d.time_zone <> (u.raw_user_meta_data ->> 'time_zone'))
            """;

    /** Projection rows whose Auth user no longer exists but are not marked deleted. */
    private static final String ORPHANED = """
            select count(*) from identity.directory_user d
            where not d.deleted and not exists (select 1 from identity.auth_users_source() u where u.id = d.user_id)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PrintStream out;

    @Autowired
    public ReconcileCommand(JdbcTemplate jdbc, TransactionTemplate tx) {
        this(jdbc, tx, System.out);
    }

    ReconcileCommand(JdbcTemplate jdbc, TransactionTemplate tx, PrintStream out) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.out = out;
    }

    public int run(String[] args) {
        if (args.length != 1 || !(args[0].equals("--check") || args[0].equals("--repair"))) {
            out.println("usage: reconcile --check|--repair");
            return EXIT_USAGE;
        }
        if (args[0].equals("--check")) {
            Summary summary = check();
            out.println("reconcile check: " + summary.describe());
            return summary.hasDrift() ? EXIT_DRIFT : EXIT_OK;
        }
        Summary repaired = repair();
        out.println("reconcile repair: " + repaired.describe());
        Summary after = check();
        out.println("reconcile check after repair: " + after.describe());
        return after.hasDrift() ? EXIT_DRIFT : EXIT_OK;
    }

    public Summary check() {
        long checked = count("select count(*) from identity.auth_users_source()");
        long drifted = count("select count(*) from (" + DRIFTED_USERS + ") x");
        long orphaned = count(ORPHANED);
        return new Summary(checked, drifted, orphaned);
    }

    public Summary repair() {
        return tx.execute(status -> {
            long checked = count("select count(*) from identity.auth_users_source()");
            // Re-project drifted users through the same function the trigger uses.
            long drifted = count("""
                    with drifted as (%s)
                    select count(*) from (
                        select identity.project_auth_user(u.id, u.email, u.raw_user_meta_data,
                                                          u.email_confirmed_at, u.phone_confirmed_at,
                                                          u.banned_until, u.deleted_at)
                        from identity.auth_users_source() u join drifted using (id)) projected
                    """.formatted(DRIFTED_USERS));
            long orphaned = jdbc.update("""
                    update identity.directory_user d
                       set active = false, deleted = true, updated_at = now()
                     where not d.deleted
                       and not exists (select 1 from identity.auth_users_source() u where u.id = d.user_id)
                    """);
            return new Summary(checked, drifted, orphaned);
        });
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    /** Counts only, safe to log. */
    public record Summary(long checked, long drifted, long orphaned) {
        boolean hasDrift() {
            return drifted > 0 || orphaned > 0;
        }

        String describe() {
            return "checked=%d drifted=%d orphaned=%d".formatted(checked, drifted, orphaned);
        }
    }
}
