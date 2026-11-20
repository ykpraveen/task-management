package com.example.tasks.service;

import com.example.tasks.domain.MutationReceipt;
import com.example.tasks.domain.Task;
import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import com.example.tasks.persistence.MutationReceiptRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an idempotent task mutation and stores its receipt in the same transaction as the task
 * change and its history (data-model.md, MutationReceipt). A retry with the same key:
 * <ol>
 *   <li>and a different request is an {@code IDEMPOTENCY_CONFLICT} (409);</li>
 *   <li>and the same request replays the stored response, after rechecking that the caller still
 *       has access to the task, and before any version check, so a lost-response retry carrying
 *       its original If-Match succeeds.</li>
 * </ol>
 * Failed mutations roll back and store nothing, so they can be retried. Concurrent requests with
 * one key serialize on the receipt's primary key; the loser replays the winner's receipt.
 */
@Component
public class TaskMutationTransaction {

    static final Pattern REFERENCE = Pattern.compile("task/([1-9][0-9]{0,18})@([1-9][0-9]{0,18})");

    private final MutationReceiptRepository receipts;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final Clock clock;

    public TaskMutationTransaction(MutationReceiptRepository receipts, PlatformTransactionManager transactionManager,
            JsonMapper json, Clock clock) {
        this.receipts = receipts;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param accessCheck rechecks the caller's current access to the referenced task before a
     *     replay; throws (typically 404) when it is gone
     * @param mutation performs the change inside the transaction and describes the response
     */
    public Outcome execute(Request request, LongConsumer accessCheck, Supplier<Result> mutation) {
        Optional<Outcome> replay = replay(request, accessCheck);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transaction.execute(status -> {
                Result result = mutation.get();
                String body = json.writeValueAsString(result.body());
                Instant now = clock.instant();
                receipts.insert(new MutationReceipt(request.actor(), request.operation(), request.key(), request.hash(),
                        result.status(), body, reference(result.task()), now, now.plus(MutationReceipt.RETENTION)));
                return new Outcome(result.status(), body, result.task().id(), result.task().version());
            });
        } catch (DuplicateKeyException e) {
            return replay(request, accessCheck).orElseThrow(() -> e);
        }
    }

    /** A request fingerprint over the operation, its target, the If-Match version and the body. */
    public String hash(String operation, String target, Long expectedVersion, Object body) {
        String canonical = operation + "\n" + target + "\n" + (expectedVersion == null ? "" : expectedVersion) + "\n"
                + json.writeValueAsString(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Outcome> replay(Request request, LongConsumer accessCheck) {
        return receipts.find(request.actor(), request.operation(), request.key(), clock.instant()).map(receipt -> {
            if (!receipt.requestHash().equals(request.hash())) {
                throw new ApiException(ProblemCode.IDEMPOTENCY_CONFLICT,
                        "This Idempotency-Key was already used for a different request. Use a new key for a changed request.");
            }
            Matcher reference = REFERENCE.matcher(receipt.responseReference());
            if (!reference.matches()) {
                throw new IllegalStateException("unexpected receipt reference");
            }
            long taskId = Long.parseLong(reference.group(1));
            accessCheck.accept(taskId);
            return new Outcome(receipt.responseStatus(), receipt.responseBody(), taskId, Long.parseLong(reference.group(2)));
        });
    }

    private static String reference(Task task) {
        return "task/" + task.id() + "@" + task.version();
    }

    /** The idempotency identity of one request. */
    public record Request(UUID actor, String operation, UUID key, String hash) {
    }

    /** A completed mutation: the response status and body, and the task as it is afterwards. */
    public record Result(int status, Object body, Task task) {
    }

    /** The response to send, fresh or replayed: status, JSON body, and the task's ID and version. */
    public record Outcome(int status, String body, long taskId, long taskVersion) {
    }
}
