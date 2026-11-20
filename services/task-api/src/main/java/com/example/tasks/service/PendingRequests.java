package com.example.tasks.service;

import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.ActivityEvent.Change;
import com.example.tasks.domain.DeadlineRequestState;
import com.example.tasks.persistence.ActivityEventRepository;
import com.example.tasks.persistence.DeadlineRequestRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Supersession of a task's pending deadline request (FR-021, FR-025), shared by direct deadline
 * changes, reassignment, completion and cancellation. Callers hold the task lock and record the
 * supersession before the change that caused it.
 */
final class PendingRequests {

    private PendingRequests() {
    }

    static void supersede(DeadlineRequestRepository requests, ActivityEventRepository activity, UUID actor, long taskId,
            String cause, Instant now) {
        requests.findPending(taskId).ifPresent(pending -> {
            requests.update(pending.resolve(DeadlineRequestState.SUPERSEDED, actor, now, cause));
            Map<String, Change> changes = new LinkedHashMap<>();
            changes.put("state", new Change(DeadlineRequestState.PENDING.wireValue(), DeadlineRequestState.SUPERSEDED.wireValue()));
            changes.put("resolution_cause", new Change(null, cause));
            activity.append(ActivityEvent.of(taskId, actor, ActivityEvent.REQUEST_SUPERSEDED, now, changes)
                    .forRequest(pending.id()));
        });
    }
}
