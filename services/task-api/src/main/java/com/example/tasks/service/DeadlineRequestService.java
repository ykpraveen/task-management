package com.example.tasks.service;

import com.example.tasks.directory.DirectoryRepository;
import com.example.tasks.directory.DirectoryRepository.DirectoryEntry;
import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.ActivityEvent.Change;
import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.DeadlineRequestState;
import com.example.tasks.domain.Task;
import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import com.example.tasks.persistence.ActivityEventRepository;
import com.example.tasks.persistence.DeadlineRequestRepository;
import com.example.tasks.persistence.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deadline-change requests (FR-020, FR-021). Only a distinct assignee of an open task submits;
 * the creator approves or declines; the requester withdraws. Each transition locks the task
 * first, advances the task version and appends history, so a decision made from a stale page, or
 * on a request that is no longer pending, fails with {@code STALE_VERSION} and changes nothing.
 * Supersession by task changes lives in {@link PendingRequests}.
 */
@Service
@Transactional
public class DeadlineRequestService {

    private final TaskRepository tasks;
    private final DeadlineRequestRepository requests;
    private final ActivityEventRepository activity;
    private final DirectoryRepository directory;
    private final DeadlineService deadlines;
    private final Clock clock;

    public DeadlineRequestService(TaskRepository tasks, DeadlineRequestRepository requests, ActivityEventRepository activity,
            DirectoryRepository directory, DeadlineService deadlines, Clock clock) {
        this.tasks = tasks;
        this.requests = requests;
        this.activity = activity;
        this.directory = directory;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    /**
     * Submits a pending request. The proposal must be a specific deadline different from the
     * current one; a date-only proposal resolves in the given zone, else the creator's zone.
     */
    public DeadlineRequest submit(UUID actor, long taskId, long expectedVersion, String proposedDueAt,
            String proposedTimeZone, String reason) {
        Task task = lockForParticipant(actor, taskId);
        if (!task.isDistinctAssignee(actor)) {
            throw ApiException.forbidden(task.assigneeId().equals(actor)
                    ? "Edit the deadline directly on a task assigned to yourself."
                    : "Only the assignee can request a deadline change.");
        }
        TaskService.checkVersion(task, expectedVersion);
        if (!task.status().isOpen()) {
            throw ApiException.forbidden("Deadline changes can be requested only for open tasks.");
        }

        List<ApiException.FieldError> errors = new ArrayList<>();
        Deadline proposed = null;
        try {
            proposed = deadlines.resolve(proposedDueAt, proposedTimeZone, creatorZone(task), "proposedDueAt", "proposedTimeZone");
            if (proposed == null) {
                errors.add(new ApiException.FieldError("proposedDueAt", "REQUIRED", "Enter the deadline you are proposing."));
            } else if (task.deadline() != null && proposed.dueAt().equals(task.deadline().dueAt())) {
                errors.add(new ApiException.FieldError("proposedDueAt", "UNCHANGED", "Propose a deadline different from the current one."));
            }
        } catch (ApiException e) {
            if (e.code() != ProblemCode.VALIDATION_FAILED) {
                throw e;
            }
            errors.addAll(e.fieldErrors());
        }
        String validReason = TaskService.reason(reason, "Enter why the deadline should change.", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (requests.findPending(task.id()).isPresent()) {
            throw ApiException.forbidden("A deadline request is already pending for this task.");
        }

        Instant now = clock.instant();
        DeadlineRequest request = requests.insert(DeadlineRequest.pending(task.id(), actor, proposed, validReason, now));
        tasks.update(task.nextVersion(now));

        Map<String, Change> changes = new LinkedHashMap<>();
        changes.put("state", new Change(null, DeadlineRequestState.PENDING.wireValue()));
        changes.put("proposed_due_at", new Change(null, proposed.dueAt().toString()));
        changes.put("proposed_time_zone", new Change(null, proposed.timeZone().getId()));
        changes.put("reason", new Change(null, validReason));
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.REQUEST_SUBMITTED, now, changes).forRequest(request.id()));
        return request;
    }

    /** The creator approves a pending request; its proposal becomes the deadline (FR-011 applies). */
    public DeadlineRequest approve(UUID actor, long taskId, long requestId, long expectedVersion, boolean confirmPastDeadline) {
        Decision decision = decide(actor, taskId, requestId, expectedVersion, true);
        DeadlineRequest pending = decision.request();
        Task task = decision.task();
        deadlines.requireConfirmationIfPast(pending.proposed(), confirmPastDeadline);

        Instant now = clock.instant();
        DeadlineRequest approved = pending.resolve(DeadlineRequestState.APPROVED, actor, now, null);
        requests.update(approved);
        tasks.update(task.withDeadline(pending.proposed()).nextVersion(now));

        Map<String, Change> changes = stateChange(DeadlineRequestState.APPROVED);
        TaskService.putDeadline(changes, task.deadline(), pending.proposed());
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.REQUEST_APPROVED, now, changes).forRequest(approved.id()));
        return approved;
    }

    /** The creator declines a pending request; the deadline is unchanged. */
    public DeadlineRequest decline(UUID actor, long taskId, long requestId, long expectedVersion) {
        Decision decision = decide(actor, taskId, requestId, expectedVersion, true);
        return resolve(actor, decision, DeadlineRequestState.DECLINED, ActivityEvent.REQUEST_DECLINED);
    }

    /** The requester withdraws their pending request while still the assignee. */
    public DeadlineRequest withdraw(UUID actor, long taskId, long requestId, long expectedVersion) {
        Decision decision = decide(actor, taskId, requestId, expectedVersion, false);
        return resolve(actor, decision, DeadlineRequestState.WITHDRAWN, ActivityEvent.REQUEST_WITHDRAWN);
    }

    @Transactional(readOnly = true)
    public List<DeadlineRequest> list(UUID actor, long taskId) {
        tasks.find(taskId).filter(task -> task.isParticipant(actor)).orElseThrow(ApiException::notFound);
        return requests.listByTask(taskId);
    }

    private DeadlineRequest resolve(UUID actor, Decision decision, DeadlineRequestState state, String eventType) {
        Instant now = clock.instant();
        DeadlineRequest resolved = decision.request().resolve(state, actor, now, null);
        requests.update(resolved);
        tasks.update(decision.task().nextVersion(now));
        activity.append(ActivityEvent.of(decision.task().id(), actor, eventType, now, stateChange(state))
                .forRequest(resolved.id()));
        return resolved;
    }

    /**
     * Locks the task, then checks access (404, including a request of another task), the actor's
     * role (403), the expected version and that the request is still pending (both 409).
     */
    private Decision decide(UUID actor, long taskId, long requestId, long expectedVersion, boolean byCreator) {
        Task task = lockForParticipant(actor, taskId);
        DeadlineRequest request = requests.find(requestId).filter(r -> r.taskId() == taskId)
                .orElseThrow(ApiException::notFound);
        if (byCreator && !task.isCreator(actor)) {
            throw ApiException.forbidden("Only the creator can decide on a deadline request.");
        }
        if (!byCreator && !(request.requesterId().equals(actor) && task.isDistinctAssignee(actor))) {
            throw ApiException.forbidden("Only the requester can withdraw this request.");
        }
        TaskService.checkVersion(task, expectedVersion);
        if (!request.isPending()) {
            throw new ApiException(ProblemCode.STALE_VERSION, "This request has already been resolved. Reload to see its state.",
                    List.of(), Map.of("currentVersion", Long.toString(task.version())));
        }
        return new Decision(task, request);
    }

    private Task lockForParticipant(UUID actor, long taskId) {
        return tasks.findForUpdate(taskId).filter(task -> task.isParticipant(actor)).orElseThrow(ApiException::notFound);
    }

    private ZoneId creatorZone(Task task) {
        return directory.find(task.creatorId()).map(DirectoryEntry::timeZone)
                .orElseGet(() -> task.deadline() != null ? task.deadline().timeZone() : clock.getZone());
    }

    private static Map<String, Change> stateChange(DeadlineRequestState state) {
        Map<String, Change> changes = new LinkedHashMap<>();
        changes.put("state", new Change(DeadlineRequestState.PENDING.wireValue(), state.wireValue()));
        return changes;
    }

    private record Decision(Task task, DeadlineRequest request) {
    }
}
