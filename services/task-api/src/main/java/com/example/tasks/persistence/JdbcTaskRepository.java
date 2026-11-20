package com.example.tasks.persistence;

import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.Priority;
import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code task.task} as the runtime role. IDs come from the identity column via
 * {@code RETURNING}; sequences are never read, reset or reseeded.
 */
@Repository
public class JdbcTaskRepository implements TaskRepository {

    static final String COLUMNS = """
            id, creator_id, assignee_id, title, description, priority, status, due_at, due_time_zone,
            blocked_reason, cancellation_reason, created_at, updated_at, completed_at, version""";

    private final JdbcTemplate jdbc;

    public JdbcTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Task insert(Task task) {
        if (task.id() != null) {
            throw new IllegalArgumentException("a new task has no ID");
        }
        Long id = jdbc.queryForObject("""
                insert into task.task (creator_id, assignee_id, title, description, priority, status, due_at,
                    due_time_zone, blocked_reason, cancellation_reason, created_at, updated_at, completed_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                task.creatorId(), task.assigneeId(), task.title(), task.description(), task.priority().wireValue(),
                task.status().wireValue(), dueAt(task), dueZone(task), task.blockedReason(), task.cancellationReason(),
                timestamp(task.createdAt()), timestamp(task.updatedAt()), timestamp(task.completedAt()), task.version());
        return task.withId(id);
    }

    @Override
    public Optional<Task> find(long id) {
        return single(jdbc.query("select " + COLUMNS + " from task.task where id = ?", JdbcTaskRepository::map, id));
    }

    @Override
    public Optional<Task> findForUpdate(long id) {
        return single(jdbc.query("select " + COLUMNS + " from task.task where id = ? for update",
                JdbcTaskRepository::map, id));
    }

    @Override
    public void update(Task task) {
        // The creator is immutable and is deliberately not written.
        int rows = jdbc.update("""
                update task.task
                set assignee_id = ?, title = ?, description = ?, priority = ?, status = ?, due_at = ?,
                    due_time_zone = ?, blocked_reason = ?, cancellation_reason = ?, updated_at = ?,
                    completed_at = ?, version = ?
                where id = ? and version = ?
                """,
                task.assigneeId(), task.title(), task.description(), task.priority().wireValue(),
                task.status().wireValue(), dueAt(task), dueZone(task), task.blockedReason(), task.cancellationReason(),
                timestamp(task.updatedAt()), timestamp(task.completedAt()), task.version(),
                task.id(), task.version() - 1);
        if (rows != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "task " + task.id() + " was not at version " + (task.version() - 1) + "; lock it before updating");
        }
    }

    static Task map(ResultSet rs, int row) throws SQLException {
        Instant dueAt = instant(rs, "due_at");
        String zone = rs.getString("due_time_zone");
        return new Task(
                rs.getLong("id"),
                rs.getObject("creator_id", UUID.class),
                rs.getObject("assignee_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                Priority.fromWire(rs.getString("priority")),
                TaskStatus.fromWire(rs.getString("status")),
                dueAt == null ? null : new Deadline(dueAt, ZoneId.of(zone)),
                rs.getString("blocked_reason"),
                rs.getString("cancellation_reason"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "completed_at"),
                rs.getLong("version"));
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static <T> Optional<T> single(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static OffsetDateTime dueAt(Task task) {
        return task.deadline() == null ? null : timestamp(task.deadline().dueAt());
    }

    private static String dueZone(Task task) {
        return task.deadline() == null ? null : task.deadline().timeZone().getId();
    }
}
