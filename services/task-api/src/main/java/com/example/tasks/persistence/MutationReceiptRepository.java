package com.example.tasks.persistence;

import static com.example.tasks.persistence.JdbcTaskRepository.instant;
import static com.example.tasks.persistence.JdbcTaskRepository.single;
import static com.example.tasks.persistence.JdbcTaskRepository.timestamp;

import com.example.tasks.domain.MutationReceipt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency receipts in {@code task.mutation_receipt}, unique per (actor, operation, key).
 * Expired receipts are invisible to lookups and are replaced when their key is reused.
 */
@Repository
public class MutationReceiptRepository {

    private final JdbcTemplate jdbc;

    public MutationReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MutationReceipt> find(UUID actorId, String operation, UUID key, Instant now) {
        return single(jdbc.query("""
                select actor_id, operation, idempotency_key, request_hash, response_status, response_body::text as body,
                       response_reference, created_at, expires_at
                from task.mutation_receipt
                where actor_id = ? and operation = ? and idempotency_key = ? and expires_at > ?
                """, MutationReceiptRepository::map, actorId, operation, key, timestamp(now)));
    }

    /**
     * Inserts the receipt in the caller's transaction. A concurrent request with the same key
     * makes this fail with a duplicate key once the other transaction commits.
     */
    public void insert(MutationReceipt receipt) {
        jdbc.update("delete from task.mutation_receipt where actor_id = ? and operation = ? and idempotency_key = ? "
                + "and expires_at <= ?", receipt.actorId(), receipt.operation(), receipt.idempotencyKey(),
                timestamp(receipt.createdAt()));
        jdbc.update("""
                insert into task.mutation_receipt (actor_id, operation, idempotency_key, request_hash, response_status,
                    response_body, response_reference, created_at, expires_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                """, receipt.actorId(), receipt.operation(), receipt.idempotencyKey(), receipt.requestHash(),
                receipt.responseStatus(), receipt.responseBody(), receipt.responseReference(),
                timestamp(receipt.createdAt()), timestamp(receipt.expiresAt()));
    }

    static MutationReceipt map(ResultSet rs, int row) throws SQLException {
        return new MutationReceipt(
                rs.getObject("actor_id", UUID.class),
                rs.getString("operation"),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("request_hash"),
                rs.getInt("response_status"),
                rs.getString("body"),
                rs.getString("response_reference"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"));
    }
}
