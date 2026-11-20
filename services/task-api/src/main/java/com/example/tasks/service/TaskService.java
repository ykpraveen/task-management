package com.example.tasks.service;

import com.example.tasks.directory.DirectoryRepository;
import com.example.tasks.directory.DirectoryRepository.DirectoryEntry;
import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.ActivityEvent.Change;
import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.Priority;
import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskStatus;
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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task lifecycle rules (data-model.md, State and transaction rules; FR-003, FR-011, FR-024,
 * FR-025). Every mutation locks the task, then checks in this order: access (outsiders get 404),
 * the actor's role (403), the expected version (409), the input (422) and the current state (403).
 * A successful mutation advances the version by one and appends history in the same transaction;
 * a rejected one changes nothing.
 */
@Service
@Transactional
public class TaskService {

    private final TaskRepository tasks;
    private final DeadlineRequestRepository requests;
    private final ActivityEventRepository activity;
    private final DirectoryRepository directory;
    private final DeadlineService deadlines;
    private final Clock clock;

    public TaskService(TaskRepository tasks, DeadlineRequestRepository requests, ActivityEventRepository activity,
            DirectoryRepository directory, DeadlineService deadlines, Clock clock) {
        this.tasks = tasks;
        this.requests = requests;
        this.activity = activity;
        this.directory = directory;
        this.deadlines = deadlines;
        this.clock = clock;
    }

    /** Any active user creates a To do task for an active assignee, possibly themselves. */
    public Task create(UUID actor, CreateTask command) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        String title = title(command.title(), errors);
        String description = description(command.description(), errors);
        checkAssignee(command.assigneeId(), errors);
        Deadline deadline = collect(errors, () ->
                deadlines.resolve(command.dueAt(), command.dueTimeZone(), zoneOf(actor)));
        failOn(errors);
        deadlines.requireConfirmationIfPast(deadline, command.confirmPastDeadline());

        Instant now = clock.instant();
        Priority priority = command.priority() == null ? Priority.NORMAL : command.priority();
        Task task = tasks.insert(new Task(null, actor, command.assigneeId(), title, description, priority,
                TaskStatus.TODO, deadline, null, null, now, now, null, 1));

        Map<String, Change> changes = new LinkedHashMap<>();
        changes.put("title", new Change(null, title));
        if (!description.isEmpty()) {
            changes.put("description", new Change(null, description));
        }
        changes.put("priority", new Change(null, priority.wireValue()));
        changes.put("assignee_id", new Change(null, task.assigneeId().toString()));
        putDeadline(changes, null, deadline);
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.TASK_CREATED, now, changes));
        return task;
    }

    /**
     * The field errors {@link #create} would report, without creating anything, so the API can
     * combine them with its own parse errors into one response.
     */
    @Transactional(readOnly = true)
    public List<ApiException.FieldError> validate(UUID actor, CreateTask command) {
        List<ApiException.FieldError> errors = new ArrayList<>();
        title(command.title(), errors);
        description(command.description(), errors);
        checkAssignee(command.assigneeId(), errors);
        collect(errors, () -> deadlines.resolve(command.dueAt(), command.dueTimeZone(), zoneOf(actor)));
        return errors;
    }

    @Transactional(readOnly = true)
    public Task get(UUID actor, long taskId) {
        return tasks.find(taskId).filter(task -> task.isParticipant(actor)).orElseThrow(ApiException::notFound);
    }

    /**
     * Creator-only field edits, reassignment and direct deadline changes. A changed or removed
     * deadline or a new assignee supersedes the pending deadline request.
     */
    public Task edit(UUID actor, long taskId, long expectedVersion, EditTask edit) {
        Task task = lockForParticipant(actor, taskId);
        if (!task.isCreator(actor)) {
            throw ApiException.forbidden("Only the creator can edit this task.");
        }
        checkVersion(task, expectedVersion);

        List<ApiException.FieldError> errors = new ArrayList<>();
        String title = edit.title() == null ? task.title() : title(edit.title(), errors);
        String description = edit.description() == null ? task.description() : description(edit.description(), errors);
        Priority priority = edit.priority() == null ? task.priority() : edit.priority();
        UUID assignee = edit.assigneeId() == null ? task.assigneeId() : edit.assigneeId();
        if (!assignee.equals(task.assigneeId())) {
            checkAssignee(assignee, errors);
        }
        Deadline deadline = switch (edit.deadline()) {
            case null -> task.deadline();
            case DeadlinePatch.Remove _ -> null;
            case DeadlinePatch.Set set -> collect(errors, () -> deadlines.resolve(set.dueAt(), set.dueTimeZone(), zoneOf(actor)));
        };
        failOn(errors);
        boolean deadlineChanged = !Objects.equals(deadline, task.deadline());
        if (deadlineChanged && task.status().isOpen()) {
            deadlines.requireConfirmationIfPast(deadline, edit.confirmPastDeadline());
        }

        Map<String, Change> fieldChanges = new LinkedHashMap<>();
        put(fieldChanges, "title", task.title(), title);
        put(fieldChanges, "description", task.description(), description);
        put(fieldChanges, "priority", task.priority().wireValue(), priority.wireValue());
        if (deadlineChanged) {
            putDeadline(fieldChanges, task.deadline(), deadline);
        }
        boolean reassigned = !assignee.equals(task.assigneeId());
        if (fieldChanges.isEmpty() && !reassigned) {
            return task;
        }

        Instant now = clock.instant();
        if (reassigned) {
            supersedePending(actor, task, DeadlineRequest.CAUSE_REASSIGNED, now);
        } else if (deadlineChanged) {
            supersedePending(actor, task, deadline == null
                    ? DeadlineRequest.CAUSE_DEADLINE_REMOVED : DeadlineRequest.CAUSE_DEADLINE_CHANGED, now);
        }
        Task updated = task.withFields(title, description, priority).withAssignee(assignee).withDeadline(deadline)
                .nextVersion(now);
        tasks.update(updated);
        if (!fieldChanges.isEmpty()) {
            activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.TASK_EDITED, now, fieldChanges));
        }
        if (reassigned) {
            activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.TASK_REASSIGNED, now,
                    Map.of("assignee_id", new Change(task.assigneeId().toString(), assignee.toString()))));
        }
        return updated;
    }

    /**
     * Either participant moves an open task between To do, In progress, Blocked and Done. Blocked
     * needs a reason; leaving Blocked clears it; Done records completion and supersedes the pending
     * request. Done and Cancelled tasks must be reopened first, and cancelling has its own operation.
     */
    public Task changeStatus(UUID actor, long taskId, long expectedVersion, TaskStatus target, String reason) {
        Task task = lockForParticipant(actor, taskId);
        checkVersion(task, expectedVersion);
        if (target == null || target == TaskStatus.CANCELLED) {
            throw ApiException.validation("status", "UNSUPPORTED_STATUS",
                    "Choose To do, In progress, Blocked or Done. Use Cancel task to cancel.");
        }
        if (!task.status().isOpen()) {
            throw ApiException.forbidden("Reopen the task before changing its status.");
        }
        String blockedReason = null;
        if (target == TaskStatus.BLOCKED) {
            List<ApiException.FieldError> errors = new ArrayList<>();
            blockedReason = reason(reason, "Enter why the task is blocked.", errors);
            failOn(errors);
        }
        if (target == task.status() && Objects.equals(blockedReason, task.blockedReason())) {
            return task;
        }

        Instant now = clock.instant();
        Instant completedAt = target == TaskStatus.DONE ? now : null;
        if (target == TaskStatus.DONE) {
            supersedePending(actor, task, DeadlineRequest.CAUSE_COMPLETED, now);
        }
        Task updated = task.withStatus(target, blockedReason, null, completedAt).nextVersion(now);
        tasks.update(updated);

        Map<String, Change> changes = new LinkedHashMap<>();
        put(changes, "status", task.status().wireValue(), target.wireValue());
        put(changes, "blocked_reason", task.blockedReason(), blockedReason);
        if (completedAt != null) {
            changes.put("completed_at", new Change(null, completedAt.toString()));
        }
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.STATUS_CHANGED, now, changes));
        return updated;
    }

    /**
     * Reopens to To do: Done by either participant, Cancelled by the creator only. A dated task
     * whose deadline has passed needs confirmation (FR-011).
     */
    public Task reopen(UUID actor, long taskId, long expectedVersion, boolean confirmPastDeadline) {
        Task task = lockForParticipant(actor, taskId);
        if (task.status() == TaskStatus.CANCELLED && !task.isCreator(actor)) {
            throw ApiException.forbidden("Only the creator can reopen a cancelled task.");
        }
        checkVersion(task, expectedVersion);
        if (task.status().isOpen()) {
            throw ApiException.forbidden("Only a done or cancelled task can be reopened.");
        }
        deadlines.requireConfirmationIfPast(task.deadline(), confirmPastDeadline);

        Instant now = clock.instant();
        Task updated = task.withStatus(TaskStatus.TODO, null, null, null).nextVersion(now);
        tasks.update(updated);

        Map<String, Change> changes = new LinkedHashMap<>();
        put(changes, "status", task.status().wireValue(), TaskStatus.TODO.wireValue());
        put(changes, "completed_at", task.completedAt() == null ? null : task.completedAt().toString(), null);
        put(changes, "cancellation_reason", task.cancellationReason(), null);
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.TASK_REOPENED, now, changes));
        return updated;
    }

    /**
     * The creator cancels an open task with a reason, keeping its content and deadline; reminders
     * stop and the pending request is superseded (FR-024).
     */
    public Task cancel(UUID actor, long taskId, long expectedVersion, String reason) {
        Task task = lockForParticipant(actor, taskId);
        if (!task.isCreator(actor)) {
            throw ApiException.forbidden("Only the creator can cancel this task.");
        }
        checkVersion(task, expectedVersion);
        List<ApiException.FieldError> errors = new ArrayList<>();
        String cancellationReason = reason(reason, "Enter why the task is cancelled.", errors);
        failOn(errors);
        if (!task.status().isOpen()) {
            throw ApiException.forbidden("Only an open task can be cancelled.");
        }

        Instant now = clock.instant();
        supersedePending(actor, task, DeadlineRequest.CAUSE_CANCELLED, now);
        Task updated = task.withStatus(TaskStatus.CANCELLED, null, cancellationReason, null).nextVersion(now);
        tasks.update(updated);

        Map<String, Change> changes = new LinkedHashMap<>();
        put(changes, "status", task.status().wireValue(), TaskStatus.CANCELLED.wireValue());
        put(changes, "blocked_reason", task.blockedReason(), null);
        changes.put("cancellation_reason", new Change(null, cancellationReason));
        activity.append(ActivityEvent.of(task.id(), actor, ActivityEvent.TASK_CANCELLED, now, changes));
        return updated;
    }

    // ------------------------------------------------------------------ shared checks

    private Task lockForParticipant(UUID actor, long taskId) {
        return tasks.findForUpdate(taskId).filter(task -> task.isParticipant(actor)).orElseThrow(ApiException::notFound);
    }

    /** Called only after access is established, so the current version leaks to participants only. */
    static void checkVersion(Task task, long expectedVersion) {
        if (task.version() != expectedVersion) {
            throw new ApiException(ProblemCode.STALE_VERSION, "The task was changed by someone else. Reload to see the changes.",
                    List.of(), Map.of("currentVersion", Long.toString(task.version())));
        }
    }

    private void supersedePending(UUID actor, Task task, String cause, Instant now) {
        PendingRequests.supersede(requests, activity, actor, task.id(), cause, now);
    }

    private void checkAssignee(UUID assignee, List<ApiException.FieldError> errors) {
        if (assignee == null) {
            errors.add(new ApiException.FieldError("assigneeId", "REQUIRED", "Choose who the task is assigned to."));
            return;
        }
        boolean active = directory.find(assignee).map(DirectoryEntry::active).orElse(false);
        if (!active) {
            errors.add(new ApiException.FieldError("assigneeId", "INACTIVE_USER", "Choose an active colleague."));
        }
    }

    /** The actor's current directory zone, used for date-only deadlines. */
    private ZoneId zoneOf(UUID actor) {
        return directory.find(actor).map(DirectoryEntry::timeZone)
                .orElseThrow(() -> new ApiException(ProblemCode.UNAUTHENTICATED, "The account is not active."));
    }

    private static String title(String value, List<ApiException.FieldError> errors) {
        String title = value == null ? "" : value.strip();
        if (title.isEmpty()) {
            errors.add(new ApiException.FieldError("title", "REQUIRED", "Enter a title."));
        } else if (Task.length(title) > Task.TITLE_MAX) {
            errors.add(new ApiException.FieldError("title", "TOO_LONG", "The title can be at most 200 characters."));
        }
        return title;
    }

    private static String description(String value, List<ApiException.FieldError> errors) {
        String description = value == null ? "" : value;
        if (Task.length(description) > Task.DESCRIPTION_MAX) {
            errors.add(new ApiException.FieldError("description", "TOO_LONG",
                    "The description can be at most 20,000 characters."));
        }
        return description;
    }

    static String reason(String value, String missing, List<ApiException.FieldError> errors) {
        String reason = value == null ? "" : value.strip();
        if (reason.isEmpty()) {
            errors.add(new ApiException.FieldError("reason", "REQUIRED", missing));
        } else if (Task.length(reason) > Task.REASON_MAX) {
            errors.add(new ApiException.FieldError("reason", "TOO_LONG", "The reason can be at most 2,000 characters."));
        }
        return reason;
    }

    /** Runs a resolver, adding its field errors to {@code errors} instead of throwing them. */
    private static <T> T collect(List<ApiException.FieldError> errors, Supplier<T> resolver) {
        try {
            return resolver.get();
        } catch (ApiException e) {
            if (e.code() != ProblemCode.VALIDATION_FAILED) {
                throw e;
            }
            errors.addAll(e.fieldErrors());
            return null;
        }
    }

    private static void failOn(List<ApiException.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }

    private static void put(Map<String, Change> changes, String field, String previous, String next) {
        if (!Objects.equals(previous, next)) {
            changes.put(field, new Change(previous, next));
        }
    }

    static void putDeadline(Map<String, Change> changes, Deadline previous, Deadline next) {
        put(changes, "due_at", previous == null ? null : previous.dueAt().toString(), next == null ? null : next.dueAt().toString());
        put(changes, "due_time_zone", previous == null ? null : previous.timeZone().getId(),
                next == null ? null : next.timeZone().getId());
    }

    // ------------------------------------------------------------------ commands

    /** POST /tasks input; {@code dueAt}/{@code dueTimeZone} are parsed by {@link DeadlineService}. */
    public record CreateTask(String title, UUID assigneeId, String description, Priority priority, String dueAt,
            String dueTimeZone, boolean confirmPastDeadline) {
    }

    /** PATCH /tasks/{id} input: a null field is left unchanged; {@code deadline} sets or removes the date. */
    public record EditTask(String title, String description, Priority priority, UUID assigneeId, DeadlinePatch deadline,
            boolean confirmPastDeadline) {

        public static EditTask none() {
            return new EditTask(null, null, null, null, null, false);
        }

        public EditTask withTitle(String value) {
            return new EditTask(value, description, priority, assigneeId, deadline, confirmPastDeadline);
        }

        public EditTask withDescription(String value) {
            return new EditTask(title, value, priority, assigneeId, deadline, confirmPastDeadline);
        }

        public EditTask withPriority(Priority value) {
            return new EditTask(title, description, value, assigneeId, deadline, confirmPastDeadline);
        }

        public EditTask withAssignee(UUID value) {
            return new EditTask(title, description, priority, value, deadline, confirmPastDeadline);
        }

        public EditTask withDeadline(DeadlinePatch value) {
            return new EditTask(title, description, priority, assigneeId, value, confirmPastDeadline);
        }

        public EditTask confirmingPastDeadline() {
            return new EditTask(title, description, priority, assigneeId, deadline, true);
        }
    }

    /** A direct deadline change: set from raw input, or remove (FR-025). */
    public sealed interface DeadlinePatch {

        record Set(String dueAt, String dueTimeZone) implements DeadlinePatch {
        }

        record Remove() implements DeadlinePatch {
        }
    }
}
