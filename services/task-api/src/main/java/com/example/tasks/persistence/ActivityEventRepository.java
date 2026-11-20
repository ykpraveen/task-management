package com.example.tasks.persistence;

import com.example.tasks.domain.ActivityEvent;

/** Append-only task history in {@code task.activity_event}. */
public interface ActivityEventRepository {

    /** Appends an event without an ID and returns it with the generated ID. */
    ActivityEvent append(ActivityEvent event);
}
