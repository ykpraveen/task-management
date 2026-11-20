package com.example.tasks.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** A deadline instant with the IANA zone it was set in; both present or the whole deadline absent. */
public record Deadline(Instant dueAt, ZoneId timeZone) {

    public Deadline {
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(timeZone, "timeZone");
    }
}
