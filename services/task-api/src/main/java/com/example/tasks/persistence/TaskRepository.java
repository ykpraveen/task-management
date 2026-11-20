package com.example.tasks.persistence;

import com.example.tasks.domain.Task;
import java.util.Optional;

/** Task rows in {@code task.task}. Mutations lock the task first ({@link #findForUpdate}). */
public interface TaskRepository {

    /** Inserts a task without an ID and returns it with the generated ID. */
    Task insert(Task task);

    Optional<Task> find(long id);

    /** Reads and row-locks the task ({@code FOR UPDATE}) for the rest of the transaction. */
    Optional<Task> findForUpdate(long id);

    /** Writes a locked task whose version is exactly one more than the stored version. */
    void update(Task task);
}
