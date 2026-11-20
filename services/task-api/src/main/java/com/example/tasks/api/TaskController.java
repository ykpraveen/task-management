package com.example.tasks.api;

import static com.example.tasks.api.TaskDtoMapper.etag;

import com.example.tasks.api.TaskDtoMapper.DeadlineRequestDecisionDto;
import com.example.tasks.api.TaskDtoMapper.DeadlineRequestDto;
import com.example.tasks.api.TaskDtoMapper.Page;
import com.example.tasks.api.TaskDtoMapper.TaskDto;
import com.example.tasks.directory.DirectoryRepository;
import com.example.tasks.directory.DirectoryRepository.SearchCursor;
import com.example.tasks.directory.DirectoryRepository.UserSummary;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.Priority;
import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskStatus;
import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import com.example.tasks.persistence.TaskQueries;
import com.example.tasks.persistence.TaskQueries.TaskPosition;
import com.example.tasks.security.CurrentUser;
import com.example.tasks.service.DeadlineRequestService;
import com.example.tasks.service.TaskMutationTransaction;
import com.example.tasks.service.TaskMutationTransaction.Outcome;
import com.example.tasks.service.TaskMutationTransaction.Result;
import com.example.tasks.service.TaskService;
import com.example.tasks.service.TaskService.CreateTask;
import com.example.tasks.service.TaskService.DeadlinePatch;
import com.example.tasks.service.TaskService.EditTask;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The US1 task API (contracts/http-api.md): user search, task create/list/detail/edit, status,
 * reopen, cancel and deadline requests. Inaccessible and unknown tasks are both 404; a
 * participant's disallowed operation is 403. Mutations require a UUID Idempotency-Key, and task
 * mutations require If-Match; both are checked before any work, and a matching receipt is
 * replayed before the version check (see {@link TaskMutationTransaction}).
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {

    /** List parameters this release accepts; the remaining contract filters arrive with US3. */
    static final Set<String> LIST_PARAMETERS = Set.of("view", "sort", "limit", "cursor");
    static final Set<String> EDIT_FIELDS = Set.of("title", "description", "priority", "assigneeId", "dueAt",
            "dueTimeZone", "confirmPastDeadline");

    private final TaskService tasks;
    private final DeadlineRequestService requests;
    private final TaskMutationTransaction mutations;
    private final TaskQueries queries;
    private final DirectoryRepository directory;
    private final TaskDtoMapper mapper;

    public TaskController(TaskService tasks, DeadlineRequestService requests, TaskMutationTransaction mutations,
            TaskQueries queries, DirectoryRepository directory, TaskDtoMapper mapper) {
        this.tasks = tasks;
        this.requests = requests;
        this.mutations = mutations;
        this.queries = queries;
        this.directory = directory;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/users")
    Page<TaskDtoMapper.UserDto> users(@RequestParam(required = false) String query,
            @RequestParam(required = false) String limit, @RequestParam(required = false) String cursor) {
        int size = RequestValues.limit(limit);
        SearchCursor after = null;
        if (cursor != null) {
            List<String> parts = RequestValues.cursorParts(cursor, "users:" + normalized(query), 2);
            after = new SearchCursor(parts.get(0), uuid(parts.get(1), "cursor"));
        }
        List<UserSummary> found = directory.search(query, size + 1, after);
        List<UserSummary> page = found.subList(0, Math.min(size, found.size()));
        String next = found.size() > size
                ? RequestValues.cursor("users:" + normalized(query), page.getLast().displayName(), page.getLast().id().toString())
                : null;
        return new Page<>(page.stream().map(TaskDtoMapper::user).toList(), next);
    }

    // ------------------------------------------------------------------ tasks

    @PostMapping("/tasks")
    ResponseEntity<String> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CreateTaskBody body) {
        UUID actor = CurrentUser.require().id();
        UUID idempotencyKey = RequestValues.idempotencyKey(key);

        List<ApiException.FieldError> errors = new ArrayList<>();
        UUID assignee = body.assigneeId() == null ? null : uuid(body.assigneeId(), "assigneeId", errors);
        Priority priority = body.priority() == null ? null : priority(body.priority(), errors);
        CreateTask command = new CreateTask(body.title(), assignee, body.description(), priority, body.dueAt(),
                body.dueTimeZone(), Boolean.TRUE.equals(body.confirmPastDeadline()));
        if (!errors.isEmpty()) {
            Set<String> reported = new HashSet<>(errors.stream().map(ApiException.FieldError::field).toList());
            tasks.validate(actor, command).stream().filter(e -> !reported.contains(e.field())).forEach(errors::add);
            throw ApiException.validation(errors);
        }

        Outcome outcome = mutate(actor, "create_task", idempotencyKey, "", null, body, () -> {
            Task task = tasks.create(actor, command);
            return new Result(HttpStatus.CREATED.value(), mapper.task(task, actor), task);
        });
        return respond(outcome, URI.create("/api/v1/tasks/" + outcome.taskId()));
    }

    @GetMapping("/tasks")
    Page<TaskDto> list(HttpServletRequest request, @RequestParam(required = false) String view,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        UUID actor = CurrentUser.require().id();
        for (String name : request.getParameterMap().keySet()) {
            if (!LIST_PARAMETERS.contains(name)) {
                throw new ApiException(ProblemCode.VALIDATION_FAILED, "The request uses a list parameter that is not supported.");
            }
        }
        TaskQueries.View listView = switch (view == null ? "" : view) {
            case "assigned" -> TaskQueries.View.ASSIGNED;
            case "created" -> TaskQueries.View.CREATED;
            default -> throw ApiException.validation("view", "INVALID_VIEW", "Choose the assigned or created view.");
        };
        if (sort != null && !sort.equals("dueAsc")) {
            throw ApiException.validation("sort", "UNSUPPORTED_SORT", "This sort order is not supported.");
        }
        int size = RequestValues.limit(limit);
        String scope = "tasks:" + listView.name();
        TaskPosition after = null;
        if (cursor != null) {
            List<String> parts = RequestValues.cursorParts(cursor, scope, 3);
            try {
                after = new TaskPosition(parts.get(0).isEmpty() ? null : Instant.parse(parts.get(0)),
                        Instant.parse(parts.get(1)), Long.parseLong(parts.get(2)));
            } catch (RuntimeException e) {
                throw ApiException.validation("cursor", "INVALID_CURSOR", "The page cursor is invalid. Load the first page again.");
            }
        }
        List<Task> found = queries.listForParticipant(actor, listView, after, size + 1);
        List<Task> page = found.subList(0, Math.min(size, found.size()));
        String next = null;
        if (found.size() > size) {
            Task last = page.getLast();
            next = RequestValues.cursor(scope, last.deadline() == null ? "" : last.deadline().dueAt().toString(),
                    last.createdAt().toString(), last.id().toString());
        }
        return new Page<>(mapper.tasks(page, actor), next);
    }

    @GetMapping("/tasks/{id}")
    ResponseEntity<TaskDto> get(@PathVariable String id) {
        UUID actor = CurrentUser.require().id();
        Task task = tasks.get(actor, RequestValues.id(id, "id"));
        return ResponseEntity.ok().eTag(etag(task.version())).body(mapper.task(task, actor));
    }

    @PatchMapping("/tasks/{id}")
    ResponseEntity<String> edit(@PathVariable String id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        EditTask edit = editTask(body);
        Outcome outcome = mutate(actor, "edit_task", idempotencyKey, id, version, new TreeMap<>(body), () -> {
            Task task = tasks.edit(actor, taskId, version, edit);
            return new Result(HttpStatus.OK.value(), mapper.task(task, actor), task);
        });
        return respond(outcome, null);
    }

    @PostMapping("/tasks/{id}/status")
    ResponseEntity<String> changeStatus(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody StatusBody body) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        TaskStatus target = TaskStatus.fromWire(body.status());
        if (target == null) {
            throw ApiException.validation("status", "INVALID_STATUS", "Choose To do, In progress, Blocked or Done.");
        }
        Outcome outcome = mutate(actor, "change_status", idempotencyKey, id, version, body, () -> {
            Task task = tasks.changeStatus(actor, taskId, version, target, body.reason());
            return new Result(HttpStatus.OK.value(), mapper.task(task, actor), task);
        });
        return respond(outcome, null);
    }

    @PostMapping("/tasks/{id}/reopen")
    ResponseEntity<String> reopen(@PathVariable String id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ConfirmBody body) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        ConfirmBody confirm = body == null ? new ConfirmBody(null) : body;
        Outcome outcome = mutate(actor, "reopen_task", idempotencyKey, id, version, confirm, () -> {
            Task task = tasks.reopen(actor, taskId, version, confirm.confirmed());
            return new Result(HttpStatus.OK.value(), mapper.task(task, actor), task);
        });
        return respond(outcome, null);
    }

    @PostMapping("/tasks/{id}/cancel")
    ResponseEntity<String> cancel(@PathVariable String id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ReasonBody body) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        Outcome outcome = mutate(actor, "cancel_task", idempotencyKey, id, version, body, () -> {
            Task task = tasks.cancel(actor, taskId, version, body.reason());
            return new Result(HttpStatus.OK.value(), mapper.task(task, actor), task);
        });
        return respond(outcome, null);
    }

    // ------------------------------------------------------------------ deadline requests

    @GetMapping("/tasks/{id}/deadline-requests")
    Page<DeadlineRequestDto> listRequests(@PathVariable String id, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        int size = RequestValues.limit(limit);
        List<DeadlineRequest> all = requests.list(actor, taskId);
        int start = 0;
        if (cursor != null) {
            long afterId = RequestValues.id(RequestValues.cursorParts(cursor, "requests:" + taskId, 1).getFirst(), "cursor");
            while (start < all.size() && all.get(start).id() != afterId) {
                start++;
            }
            start++;
        }
        List<DeadlineRequest> page = all.subList(Math.min(start, all.size()), Math.min(start + size, all.size()));
        String next = start + size < all.size() ? RequestValues.cursor("requests:" + taskId, page.getLast().id().toString()) : null;
        return new Page<>(mapper.requests(page), next);
    }

    @PostMapping("/tasks/{id}/deadline-requests")
    ResponseEntity<String> submitRequest(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody DeadlineRequestBody body) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        Outcome outcome = mutate(actor, "submit_deadline_request", idempotencyKey, id, version, body, () -> {
            DeadlineRequest request = requests.submit(actor, taskId, version, body.proposedDueAt(), body.proposedTimeZone(),
                    body.reason());
            return new Result(HttpStatus.CREATED.value(), mapper.request(request), tasks.get(actor, taskId));
        });
        return respond(outcome, null);
    }

    @PostMapping("/tasks/{id}/deadline-requests/{requestId}/approve")
    ResponseEntity<String> approve(@PathVariable String id, @PathVariable String requestId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) ConfirmBody body) {
        ConfirmBody confirm = body == null ? new ConfirmBody(null) : body;
        return decide(id, requestId, ifMatch, key, "approve_deadline_request", confirm,
                (actor, taskId, request, version) -> requests.approve(actor, taskId, request, version, confirm.confirmed()));
    }

    @PostMapping("/tasks/{id}/deadline-requests/{requestId}/decline")
    ResponseEntity<String> decline(@PathVariable String id, @PathVariable String requestId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return decide(id, requestId, ifMatch, key, "decline_deadline_request", null, requests::decline);
    }

    @PostMapping("/tasks/{id}/deadline-requests/{requestId}/withdraw")
    ResponseEntity<String> withdraw(@PathVariable String id, @PathVariable String requestId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return decide(id, requestId, ifMatch, key, "withdraw_deadline_request", null, requests::withdraw);
    }

    private ResponseEntity<String> decide(String id, String requestId, String ifMatch, String key, String operation,
            Object body, RequestDecision decision) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        long request = RequestValues.id(requestId, "requestId");
        long version = RequestValues.ifMatch(ifMatch);
        UUID idempotencyKey = RequestValues.idempotencyKey(key);
        Outcome outcome = mutate(actor, operation, idempotencyKey, id + "/" + requestId, version, body, () -> {
            DeadlineRequest decided = decision.apply(actor, taskId, request, version);
            Task task = tasks.get(actor, taskId);
            return new Result(HttpStatus.OK.value(),
                    new DeadlineRequestDecisionDto(mapper.request(decided), mapper.task(task, actor)), task);
        });
        return respond(outcome, null);
    }

    // ------------------------------------------------------------------ helpers

    private Outcome mutate(UUID actor, String operation, UUID key, String target, Long version, Object body,
            Supplier<Result> mutation) {
        TaskMutationTransaction.Request request = new TaskMutationTransaction.Request(actor, operation, key,
                mutations.hash(operation, target, version, body));
        return mutations.execute(request, taskId -> tasks.get(actor, taskId), mutation);
    }

    private static ResponseEntity<String> respond(Outcome outcome, URI location) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(outcome.status()).eTag(etag(outcome.taskVersion()))
                .contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            response.location(location);
        }
        return response.body(outcome.body());
    }

    /** PATCH semantics: absent fields are unchanged; a null dueAt/dueTimeZone pair removes the deadline. */
    static EditTask editTask(Map<String, Object> body) {
        if (!EDIT_FIELDS.containsAll(body.keySet())) {
            throw new ApiException(ProblemCode.VALIDATION_FAILED,
                    "The request contains fields that cannot be edited.");
        }
        List<ApiException.FieldError> errors = new ArrayList<>();
        EditTask edit = EditTask.none();
        if (body.containsKey("title")) {
            edit = edit.withTitle(text(body, "title", errors, ""));
        }
        if (body.containsKey("description")) {
            edit = edit.withDescription(text(body, "description", errors, ""));
        }
        if (body.containsKey("priority")) {
            edit = edit.withPriority(priority(text(body, "priority", errors, ""), errors));
        }
        if (body.containsKey("assigneeId")) {
            String assignee = text(body, "assigneeId", errors, "");
            edit = edit.withAssignee(uuid(assignee, "assigneeId", errors));
        }
        if (body.containsKey("dueAt") || body.containsKey("dueTimeZone")) {
            String dueAt = text(body, "dueAt", errors, null);
            String zone = text(body, "dueTimeZone", errors, null);
            edit = edit.withDeadline(dueAt == null && zone == null ? new DeadlinePatch.Remove() : new DeadlinePatch.Set(dueAt, zone));
        }
        Object confirm = body.get("confirmPastDeadline");
        if (confirm != null && !(confirm instanceof Boolean)) {
            errors.add(new ApiException.FieldError("confirmPastDeadline", "INVALID", "Must be true or false."));
        } else if (Boolean.TRUE.equals(confirm)) {
            edit = edit.confirmingPastDeadline();
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return edit;
    }

    private static String text(Map<String, Object> body, String field, List<ApiException.FieldError> errors, String ifNull) {
        Object value = body.get(field);
        if (value == null) {
            return ifNull;
        }
        if (value instanceof String text) {
            return text;
        }
        errors.add(new ApiException.FieldError(field, "INVALID", "Must be text."));
        return ifNull;
    }

    private static Priority priority(String value, List<ApiException.FieldError> errors) {
        Priority priority = Priority.fromWire(value);
        if (priority == null) {
            errors.add(new ApiException.FieldError("priority", "INVALID_PRIORITY", "Choose Low, Normal or High."));
        }
        return priority;
    }

    private static UUID uuid(String value, String field, List<ApiException.FieldError> errors) {
        try {
            return uuid(value, field);
        } catch (ApiException e) {
            errors.addAll(e.fieldErrors());
            return null;
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            // fall through
        }
        throw ApiException.validation(field, "INVALID_USER_ID", "Choose a person from the list.");
    }

    private static String normalized(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface RequestDecision {
        DeadlineRequest apply(UUID actor, long taskId, long requestId, long version);
    }

    // ------------------------------------------------------------------ bodies

    record CreateTaskBody(String title, String assigneeId, String description, String priority, String dueAt,
            String dueTimeZone, Boolean confirmPastDeadline) {
    }

    record StatusBody(String status, String reason) {
    }

    record ReasonBody(String reason) {
    }

    record ConfirmBody(Boolean confirmPastDeadline) {
        boolean confirmed() {
            return Boolean.TRUE.equals(confirmPastDeadline);
        }
    }

    record DeadlineRequestBody(String proposedDueAt, String proposedTimeZone, String reason) {
    }
}
