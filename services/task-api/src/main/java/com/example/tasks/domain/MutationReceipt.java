package com.example.tasks.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The stored outcome of an idempotent mutation (data-model.md, MutationReceipt), unique per
 * {@code (actorId, operation, idempotencyKey)} and retained at least seven days.
 *
 * @param responseBody the serialized response replayed on retry; null for empty responses
 * @param responseReference the affected resource, so a replay can recheck current access
 */
public record MutationReceipt(
        UUID actorId,
        String operation,
        UUID idempotencyKey,
        String requestHash,
        int responseStatus,
        String responseBody,
        String responseReference,
        Instant createdAt,
        Instant expiresAt) {

    public static final Duration RETENTION = Duration.ofDays(7);

    public MutationReceipt {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Task.require(operation.matches("[a-z][a-z_]{0,63}"), "operation must be a lowercase identifier");
        Task.require(responseStatus >= 200 && responseStatus <= 599, "response status out of range");
        Task.require(!expiresAt.isBefore(createdAt.plus(RETENTION)), "receipts are retained at least seven days");
    }
}
