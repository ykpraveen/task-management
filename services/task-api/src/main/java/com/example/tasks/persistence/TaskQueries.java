package com.example.tasks.persistence;

import static com.example.tasks.persistence.JdbcTaskRepository.instant;
import static com.example.tasks.persistence.JdbcTaskRepository.timestamp;

import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.Task;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side keyset queries: participant task lists and task history. Pages are stable under
 * concurrent inserts because every order ends with the unique {@code id}.
 */
@Repository
public class TaskQueries {

    private final JdbcTemplate jdbc;

    public TaskQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The caller's assigned or created tasks, earliest deadline first with undated tasks last,
     * then {@code (created_at, id)}.
     */
    public List<Task> listForParticipant(UUID userId, View view, TaskPosition after, int limit) {
        String participant = view == View.ASSIGNED ? "assignee_id" : "creator_id";
        String select = "select " + JdbcTaskRepository.COLUMNS + " from task.task where " + participant + " = ? ";
        String order = " order by coalesce(due_at, 'infinity'::timestamptz), created_at, id limit ?";
        if (after == null) {
            return jdbc.query(select + order, JdbcTaskRepository::map, userId, limit);
        }
        return jdbc.query(select
                + "and (coalesce(due_at, 'infinity'::timestamptz), created_at, id) "
                + "> (coalesce(cast(? as timestamptz), 'infinity'::timestamptz), ?, ?)" + order,
                JdbcTaskRepository::map, userId, timestamp(after.dueAt()), timestamp(after.createdAt()), after.id(), limit);
    }

    /** Task history in chronological {@code (occurred_at, id)} order after {@code afterId}'s position. */
    public List<ActivityEvent> history(long taskId, EventPosition after, int limit) {
        String select = """
                select e.id, e.task_id, e.actor_id, e.type, e.occurred_at, e.deadline_request_id,
                       array(select k from jsonb_object_keys(e.changes) k order by k) as fields,
                       array(select e.changes -> k ->> 'previous' from jsonb_object_keys(e.changes) k order by k) as previous,
                       array(select e.changes -> k ->> 'new' from jsonb_object_keys(e.changes) k order by k) as next
                from task.activity_event e
                where e.task_id = ?
                """;
        String order = " order by e.occurred_at, e.id limit ?";
        if (after == null) {
            return jdbc.query(select + order, TaskQueries::event, taskId, limit);
        }
        return jdbc.query(select + " and (e.occurred_at, e.id) > (?, ?)" + order, TaskQueries::event,
                taskId, timestamp(after.occurredAt()), after.id(), limit);
    }

    static ActivityEvent event(ResultSet rs, int row) throws SQLException {
        String[] fields = strings(rs.getArray("fields"));
        String[] previous = strings(rs.getArray("previous"));
        String[] next = strings(rs.getArray("next"));
        Map<String, ActivityEvent.Change> changes = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i++) {
            changes.put(fields[i], new ActivityEvent.Change(previous[i], next[i]));
        }
        long requestId = rs.getLong("deadline_request_id");
        return new ActivityEvent(rs.getLong("id"), rs.getLong("task_id"), rs.getObject("actor_id", UUID.class),
                rs.getString("type"), instant(rs, "occurred_at"), changes, rs.wasNull() ? null : requestId);
    }

    private static String[] strings(Array array) throws SQLException {
        return (String[]) array.getArray();
    }

    public enum View { ASSIGNED, CREATED }

    /** Keyset position of the last task on a page; {@code dueAt} is null for an undated task. */
    public record TaskPosition(Instant dueAt, Instant createdAt, long id) {
    }

    /** Keyset position of the last history entry on a page. */
    public record EventPosition(Instant occurredAt, long id) {
    }
}
