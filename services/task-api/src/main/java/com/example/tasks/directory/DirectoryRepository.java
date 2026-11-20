package com.example.tasks.directory;

import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the identity-owned {@code identity.directory} view (data-model.md,
 * DirectoryUser). Search returns active users with display name and email only; lookups add the
 * active state and IANA zone that task rules need. A directory failure is reported as 503
 * ({@code DEPENDENCY_UNAVAILABLE}), never as "no matches".
 */
@Repository
public class DirectoryRepository {

    public static final int QUERY_MAX = 200;
    public static final int LIMIT_MAX = 100;

    private final JdbcTemplate jdbc;
    private final ZoneId defaultZone;

    public DirectoryRepository(JdbcTemplate jdbc, @Value("${tasks.default-time-zone:UTC}") String defaultZone) {
        this.jdbc = jdbc;
        this.defaultZone = ZoneId.of(defaultZone);
    }

    /** The user's directory entry, active or not; empty when the user is unknown. */
    public Optional<DirectoryEntry> find(UUID userId) {
        try {
            List<DirectoryEntry> rows = jdbc.query("""
                    select user_id, display_name, email, active, time_zone
                    from identity.directory where user_id = ? and not deleted
                    """, this::entry, userId);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    /**
     * Active users whose display name or email contains the trimmed query, case-insensitively, in
     * stable {@code (lower(display_name), user_id)} order after {@code after} when given.
     *
     * @throws ApiException 422 for an empty or over-long query or an invalid limit; 503 when the
     *     directory cannot be read
     */
    public List<UserSummary> search(String query, int limit, SearchCursor after) {
        String trimmed = query == null ? "" : query.strip();
        if (trimmed.isEmpty()) {
            throw ApiException.validation("query", "REQUIRED", "Enter a name or email to search for.");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > QUERY_MAX) {
            throw ApiException.validation("query", "TOO_LONG", "Search text can be at most 200 characters.");
        }
        if (limit < 1 || limit > LIMIT_MAX) {
            throw ApiException.validation("limit", "OUT_OF_RANGE", "Limit must be between 1 and 100.");
        }
        String pattern = "%" + escapeLike(trimmed.toLowerCase(Locale.ROOT)) + "%";
        try {
            if (after == null) {
                return jdbc.query("""
                        select user_id, display_name, email from identity.directory
                        where active and not deleted
                          and (lower(display_name) like ? escape '\\' or lower(email) like ? escape '\\')
                        order by lower(display_name), user_id
                        limit ?
                        """, DirectoryRepository::summary, pattern, pattern, limit);
            }
            return jdbc.query("""
                    select user_id, display_name, email from identity.directory
                    where active and not deleted
                      and (lower(display_name) like ? escape '\\' or lower(email) like ? escape '\\')
                      and (lower(display_name), user_id) > (lower(?), ?)
                    order by lower(display_name), user_id
                    limit ?
                    """, DirectoryRepository::summary, pattern, pattern, after.displayName(), after.userId(), limit);
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    /**
     * Display identities for the given users, including inactive and deleted ones so history and
     * former participants still render; unknown IDs are absent from the result.
     */
    public Map<UUID, UserSummary> summaries(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<UserSummary> rows = jdbc.query(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "select user_id, display_name, email from identity.directory where user_id = any(?)");
                ps.setArray(1, connection.createArrayOf("uuid", userIds.toArray()));
                return ps;
            }, DirectoryRepository::summary);
            Map<UUID, UserSummary> byId = new HashMap<>();
            rows.forEach(summary -> byId.put(summary.id(), summary));
            return byId;
        } catch (DataAccessException e) {
            throw unavailable();
        }
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private DirectoryEntry entry(ResultSet rs, int row) throws SQLException {
        return new DirectoryEntry(rs.getObject("user_id", UUID.class), rs.getString("display_name"),
                rs.getString("email"), rs.getBoolean("active"), zone(rs.getString("time_zone")));
    }

    private static UserSummary summary(ResultSet rs, int row) throws SQLException {
        return new UserSummary(rs.getObject("user_id", UUID.class), rs.getString("display_name"), rs.getString("email"));
    }

    /** PostgreSQL validated the name; fall back only if the JVM's zone database lacks it. */
    private ZoneId zone(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException e) {
            return defaultZone;
        }
    }

    private static ApiException unavailable() {
        return new ApiException(ProblemCode.DEPENDENCY_UNAVAILABLE, "The user directory is unavailable right now.");
    }

    /** Directory facts used by task rules; never serialized to clients as-is. */
    public record DirectoryEntry(UUID userId, String displayName, String email, boolean active, ZoneId timeZone) {
    }

    /** The public identity: stable ID, display name and email only. */
    public record UserSummary(UUID id, String displayName, String email) {
    }

    /** Keyset position: the last returned user's display name and ID. */
    public record SearchCursor(String displayName, UUID userId) {
    }
}
