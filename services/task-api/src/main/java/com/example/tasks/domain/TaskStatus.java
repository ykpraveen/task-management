package com.example.tasks.domain;

import java.util.Locale;

/** Task status; wire and column values are the lowercase names (`todo|in_progress|blocked|done|cancelled`). */
public enum TaskStatus {
    TODO, IN_PROGRESS, BLOCKED, DONE, CANCELLED;

    /** Open means To do, In progress or Blocked (FR-003). */
    public boolean isOpen() {
        return this == TODO || this == IN_PROGRESS || this == BLOCKED;
    }

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a wire value; returns null for anything outside the closed set. */
    public static TaskStatus fromWire(String value) {
        for (TaskStatus status : values()) {
            if (status.wireValue().equals(value)) {
                return status;
            }
        }
        return null;
    }
}
