package com.example.tasks.persistence;

import static com.example.tasks.persistence.JdbcTaskRepository.instant;
import static com.example.tasks.persistence.JdbcTaskRepository.single;
import static com.example.tasks.persistence.JdbcTaskRepository.timestamp;

import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.DeadlineRequestState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC access to {@code task.deadline_request}. The unique partial index on pending requests is
 * the final guard for one pending request per task; callers hold the task lock.
 */
@Repository
public class JdbcDeadlineRequestRepository implements DeadlineRequestRepository {

    static final String COLUMNS = """
            id, task_id, requester_id, proposed_due_at, proposed_time_zone, reason, state, created_at,
            resolved_at, resolved_by, resolution_cause, version""";

    private final JdbcTemplate jdbc;

    public JdbcDeadlineRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DeadlineRequest insert(DeadlineRequest request) {
        if (request.id() != null) {
            throw new IllegalArgumentException("a new request has no ID");
        }
        Long id = jdbc.queryForObject("""
                insert into task.deadline_request (task_id, requester_id, proposed_due_at, proposed_time_zone, reason,
                    state, created_at, resolved_at, resolved_by, resolution_cause, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class,
                request.taskId(), request.requesterId(), timestamp(request.proposed().dueAt()),
                request.proposed().timeZone().getId(), request.reason(), request.state().wireValue(),
                timestamp(request.createdAt()), timestamp(request.resolvedAt()), request.resolvedBy(),
                request.resolutionCause(), request.version());
        return request.withId(id);
    }

    @Override
    public Optional<DeadlineRequest> find(long id) {
        return single(jdbc.query("select " + COLUMNS + " from task.deadline_request where id = ?",
                JdbcDeadlineRequestRepository::map, id));
    }

    @Override
    public Optional<DeadlineRequest> findPending(long taskId) {
        return single(jdbc.query("select " + COLUMNS + " from task.deadline_request where task_id = ? and state = 'pending'",
                JdbcDeadlineRequestRepository::map, taskId));
    }

    @Override
    public void update(DeadlineRequest request) {
        // The proposal is immutable; only the resolution is written, and only over a pending row.
        int rows = jdbc.update("""
                update task.deadline_request
                set state = ?, resolved_at = ?, resolved_by = ?, resolution_cause = ?, version = ?
                where id = ? and version = ? and state = 'pending'
                """,
                request.state().wireValue(), timestamp(request.resolvedAt()), request.resolvedBy(),
                request.resolutionCause(), request.version(), request.id(), request.version() - 1);
        if (rows != 1) {
            throw new IncorrectUpdateSemanticsDataAccessException(
                    "deadline request " + request.id() + " is no longer pending at version " + (request.version() - 1));
        }
    }

    @Override
    public List<DeadlineRequest> listByTask(long taskId) {
        return jdbc.query("select " + COLUMNS + " from task.deadline_request where task_id = ? order by created_at, id",
                JdbcDeadlineRequestRepository::map, taskId);
    }

    static DeadlineRequest map(ResultSet rs, int row) throws SQLException {
        return new DeadlineRequest(
                rs.getLong("id"),
                rs.getLong("task_id"),
                rs.getObject("requester_id", UUID.class),
                new Deadline(instant(rs, "proposed_due_at"), ZoneId.of(rs.getString("proposed_time_zone"))),
                rs.getString("reason"),
                DeadlineRequestState.fromWire(rs.getString("state")),
                instant(rs, "created_at"),
                instant(rs, "resolved_at"),
                rs.getObject("resolved_by", UUID.class),
                rs.getString("resolution_cause"),
                rs.getLong("version"));
    }
}
