package com.example.tasks.api;

import com.example.tasks.api.TaskDtoMapper.HistoryEntryDto;
import com.example.tasks.api.TaskDtoMapper.Page;
import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.operations.ApiException;
import com.example.tasks.persistence.TaskQueries;
import com.example.tasks.persistence.TaskQueries.EventPosition;
import com.example.tasks.security.CurrentUser;
import com.example.tasks.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/tasks/{id}/history}: the immutable history of a task in chronological order,
 * paged by an opaque keyset cursor. Current participants see all history, including entries from
 * before they were assigned; everyone else gets 404.
 */
@RestController
@RequestMapping("/api/v1")
public class TaskHistoryController {

    private final TaskService tasks;
    private final TaskQueries queries;
    private final TaskDtoMapper mapper;

    public TaskHistoryController(TaskService tasks, TaskQueries queries, TaskDtoMapper mapper) {
        this.tasks = tasks;
        this.queries = queries;
        this.mapper = mapper;
    }

    @GetMapping("/tasks/{id}/history")
    @Transactional(readOnly = true)
    public Page<HistoryEntryDto> history(@PathVariable String id, @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        UUID actor = CurrentUser.require().id();
        long taskId = RequestValues.id(id, "id");
        int size = RequestValues.limit(limit);
        tasks.get(actor, taskId);

        String scope = "history:" + taskId;
        EventPosition after = null;
        if (cursor != null) {
            List<String> parts = RequestValues.cursorParts(cursor, scope, 2);
            try {
                after = new EventPosition(Instant.parse(parts.get(0)), Long.parseLong(parts.get(1)));
            } catch (RuntimeException e) {
                throw ApiException.validation("cursor", "INVALID_CURSOR", "The page cursor is invalid. Load the first page again.");
            }
        }
        List<ActivityEvent> found = queries.history(taskId, after, size + 1);
        List<ActivityEvent> page = found.subList(0, Math.min(size, found.size()));
        String next = found.size() > size
                ? RequestValues.cursor(scope, page.getLast().occurredAt().toString(), page.getLast().id().toString())
                : null;
        return new Page<>(mapper.history(page), next);
    }
}
