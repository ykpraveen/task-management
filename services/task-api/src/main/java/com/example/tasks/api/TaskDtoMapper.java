package com.example.tasks.api;

import com.example.tasks.directory.DirectoryRepository;
import com.example.tasks.directory.DirectoryRepository.UserSummary;
import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskStatus;
import com.example.tasks.service.DeadlineService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Public DTOs (contracts/http-api.md). Application IDs and versions are canonical decimal
 * strings, user IDs are UUIDs, timestamps are UTC RFC 3339, deadlines are a nullable instant plus
 * zone, {@code isOverdue} is computed now, and permissions describe what the viewer may do.
 * Identities carry ID, display name and email only.
 */
@Component
public class TaskDtoMapper {

    private final DirectoryRepository directory;
    private final DeadlineService deadlines;

    public TaskDtoMapper(DirectoryRepository directory, DeadlineService deadlines) {
        this.directory = directory;
        this.deadlines = deadlines;
    }

    /** A strong ETag carrying the decimal task version. */
    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public TaskDto task(Task task, UUID viewer) {
        return tasks(List.of(task), viewer).getFirst();
    }

    public List<TaskDto> tasks(List<Task> tasks, UUID viewer) {
        Set<UUID> users = new HashSet<>();
        tasks.forEach(task -> {
            users.add(task.creatorId());
            users.add(task.assigneeId());
        });
        Map<UUID, UserSummary> summaries = directory.summaries(users);
        return tasks.stream().map(task -> new TaskDto(
                task.id().toString(),
                user(summaries, task.creatorId()),
                user(summaries, task.assigneeId()),
                task.title(),
                task.description(),
                task.priority().wireValue(),
                task.status().wireValue(),
                task.deadline() == null ? null : text(task.deadline().dueAt()),
                task.deadline() == null ? null : task.deadline().timeZone().getId(),
                task.blockedReason(),
                task.cancellationReason(),
                text(task.createdAt()),
                text(task.updatedAt()),
                text(task.completedAt()),
                deadlines.isOverdue(task),
                Long.toString(task.version()),
                permissions(task, viewer))).toList();
    }

    public DeadlineRequestDto request(DeadlineRequest request) {
        return requests(List.of(request)).getFirst();
    }

    public List<DeadlineRequestDto> requests(List<DeadlineRequest> requests) {
        Set<UUID> users = new HashSet<>();
        requests.forEach(request -> {
            users.add(request.requesterId());
            if (request.resolvedBy() != null) {
                users.add(request.resolvedBy());
            }
        });
        Map<UUID, UserSummary> summaries = directory.summaries(users);
        return requests.stream().map(request -> new DeadlineRequestDto(
                request.id().toString(),
                request.taskId().toString(),
                user(summaries, request.requesterId()),
                text(request.proposed().dueAt()),
                request.proposed().timeZone().getId(),
                request.reason(),
                request.state().wireValue(),
                text(request.createdAt()),
                text(request.resolvedAt()),
                request.resolvedBy() == null ? null : user(summaries, request.resolvedBy()),
                Long.toString(request.version()))).toList();
    }

    public List<HistoryEntryDto> history(List<ActivityEvent> events) {
        Set<UUID> actors = new HashSet<>();
        events.forEach(event -> {
            if (event.actorId() != null) {
                actors.add(event.actorId());
            }
        });
        Map<UUID, UserSummary> summaries = directory.summaries(actors);
        return events.stream().map(event -> new HistoryEntryDto(
                event.id().toString(),
                event.actorId() == null ? null : user(summaries, event.actorId()),
                event.type(),
                text(event.occurredAt()),
                event.changes().entrySet().stream()
                        .map(change -> new HistoryChangeDto(change.getKey(), change.getValue().previous(), change.getValue().next()))
                        .toList(),
                event.deadlineRequestId() == null ? null : event.deadlineRequestId().toString())).toList();
    }

    public static UserDto user(UserSummary summary) {
        return new UserDto(summary.id(), summary.displayName(), summary.email());
    }

    /**
     * Advisory permissions for the UI; the services enforce the same rules. Deadline requests are
     * for a distinct assignee of an open task, and only the creator decides them.
     */
    static PermissionsDto permissions(Task task, UUID viewer) {
        boolean creator = task.isCreator(viewer);
        boolean participant = task.isParticipant(viewer);
        boolean open = task.status().isOpen();
        boolean delegated = !task.creatorId().equals(task.assigneeId());
        return new PermissionsDto(
                creator,
                participant && open,
                (task.status() == TaskStatus.DONE && participant) || (task.status() == TaskStatus.CANCELLED && creator),
                creator && open,
                participant,
                participant,
                task.isDistinctAssignee(viewer) && open,
                creator && delegated && open);
    }

    private static UserDto user(Map<UUID, UserSummary> summaries, UUID id) {
        UserSummary summary = summaries.get(id);
        // A user missing from the directory projection still renders, without contact details.
        return summary == null ? new UserDto(id, "Unknown user", "") : user(summary);
    }

    static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // ------------------------------------------------------------------ DTOs

    public record UserDto(UUID id, String displayName, String email) {
    }

    public record PermissionsDto(boolean edit, boolean changeStatus, boolean reopen, boolean cancel, boolean comment,
            boolean upload, boolean requestDeadline, boolean decideDeadlineRequest) {
    }

    public record TaskDto(
            String id,
            UserDto creator,
            UserDto assignee,
            String title,
            String description,
            String priority,
            String status,
            String dueAt,
            String dueTimeZone,
            String blockedReason,
            String cancellationReason,
            String createdAt,
            String updatedAt,
            String completedAt,
            @JsonProperty("isOverdue") boolean isOverdue,
            String version,
            PermissionsDto permissions) {
    }

    public record DeadlineRequestDto(
            String id,
            String taskId,
            UserDto requester,
            String proposedDueAt,
            String proposedTimeZone,
            String reason,
            String state,
            String createdAt,
            String resolvedAt,
            UserDto resolvedBy,
            String version) {
    }

    /** A decided request together with the task after the decision. */
    public record DeadlineRequestDecisionDto(DeadlineRequestDto request, TaskDto task) {
    }

    public record HistoryChangeDto(String field, String previous, @JsonProperty("new") String next) {
    }

    public record HistoryEntryDto(String id, UserDto actor, String type, String occurredAt, List<HistoryChangeDto> changes,
            @JsonInclude(JsonInclude.Include.NON_NULL) String deadlineRequestId) {
    }

    /** A keyset page: {@code nextCursor} is null on the last page. */
    public record Page<T>(Collection<T> items, String nextCursor) {
    }
}
