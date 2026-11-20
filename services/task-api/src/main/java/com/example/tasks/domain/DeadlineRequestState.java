package com.example.tasks.domain;

import java.util.Locale;

/** Deadline-change request states `pending|approved|declined|withdrawn|superseded` (FR-021). */
public enum DeadlineRequestState {
    PENDING, APPROVED, DECLINED, WITHDRAWN, SUPERSEDED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DeadlineRequestState fromWire(String value) {
        for (DeadlineRequestState state : values()) {
            if (state.wireValue().equals(value)) {
                return state;
            }
        }
        return null;
    }
}
