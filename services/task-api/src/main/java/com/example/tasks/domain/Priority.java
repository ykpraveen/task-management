package com.example.tasks.domain;

import java.util.Locale;

/** Task priority; wire and column values are `low|normal|high`. */
public enum Priority {
    LOW, NORMAL, HIGH;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a wire value; returns null for anything outside the closed set. */
    public static Priority fromWire(String value) {
        for (Priority priority : values()) {
            if (priority.wireValue().equals(value)) {
                return priority;
            }
        }
        return null;
    }
}
