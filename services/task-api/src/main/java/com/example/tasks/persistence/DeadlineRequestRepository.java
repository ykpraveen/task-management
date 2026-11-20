package com.example.tasks.persistence;

import com.example.tasks.domain.DeadlineRequest;
import java.util.List;
import java.util.Optional;

/** Deadline-change requests in {@code task.deadline_request}; callers lock the task first. */
public interface DeadlineRequestRepository {

    /** Inserts a request without an ID and returns it with the generated ID. */
    DeadlineRequest insert(DeadlineRequest request);

    Optional<DeadlineRequest> find(long id);

    Optional<DeadlineRequest> findPending(long taskId);

    /** Writes a resolved request over its pending version. */
    void update(DeadlineRequest request);

    /** Every retained request of the task in stable {@code (created_at, id)} order. */
    List<DeadlineRequest> listByTask(long taskId);
}
