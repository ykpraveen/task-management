package com.example.tasks.persistence;

import static com.example.tasks.persistence.JdbcTaskRepository.timestamp;

import com.example.tasks.domain.ActivityEvent;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Appends to {@code task.activity_event}. The {@code changes} JSON object is built by PostgreSQL
 * from parallel arrays, so values are never concatenated into JSON text here.
 */
@Repository
public class JdbcActivityEventRepository implements ActivityEventRepository {

    private final JdbcTemplate jdbc;

    public JdbcActivityEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ActivityEvent append(ActivityEvent event) {
        if (event.id() != null) {
            throw new IllegalArgumentException("a new event has no ID");
        }
        List<String> fields = new ArrayList<>();
        List<String> previous = new ArrayList<>();
        List<String> next = new ArrayList<>();
        for (Map.Entry<String, ActivityEvent.Change> change : event.changes().entrySet()) {
            fields.add(change.getKey());
            previous.add(change.getValue().previous());
            next.add(change.getValue().next());
        }
        Long id = jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    insert into task.activity_event (task_id, actor_type, actor_id, type, occurred_at, changes,
                        deadline_request_id)
                    values (?, ?, ?, ?, ?,
                        coalesce((select jsonb_object_agg(f.field, jsonb_build_object('previous', f.previous, 'new', f.next))
                                  from unnest(?::text[], ?::text[], ?::text[]) as f(field, previous, next)), '{}'::jsonb),
                        ?)
                    returning id
                    """);
            ps.setLong(1, event.taskId());
            ps.setString(2, event.actorId() == null ? "system" : "user");
            ps.setObject(3, event.actorId(), Types.OTHER);
            ps.setString(4, event.type());
            ps.setObject(5, timestamp(event.occurredAt()));
            ps.setArray(6, connection.createArrayOf("text", fields.toArray()));
            ps.setArray(7, connection.createArrayOf("text", previous.toArray()));
            ps.setArray(8, connection.createArrayOf("text", next.toArray()));
            ps.setObject(9, event.deadlineRequestId(), Types.BIGINT);
            return ps;
        }, rs -> rs.next() ? rs.getLong(1) : null);
        return event.withId(id);
    }
}
