package com.example.tasks.service;

import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.Task;
import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deadline parsing and rules (FR-011, FR-025). Accepted inputs:
 * <ul>
 *   <li>a date ({@code 2026-12-01}): 23:59 that day in the given zone, else the creator's zone;</li>
 *   <li>a local date-time ({@code 2026-12-01T17:00}): that wall-clock time in the zone, rejected
 *       when a DST transition makes it nonexistent or ambiguous;</li>
 *   <li>a date-time with an offset ({@code 2027-10-31T02:30+01:00}): that exact instant, which is how
 *       a client resolves an ambiguous local time.</li>
 * </ul>
 * The stored deadline is always an instant plus the IANA zone it was set in.
 */
@Service
public class DeadlineService {

    static final LocalTime DATE_ONLY_TIME = LocalTime.of(23, 59);

    private final Clock clock;

    public DeadlineService(Clock clock) {
        this.clock = clock;
    }

    /** Resolves a task deadline; field errors use {@code dueAt} and {@code dueTimeZone}. */
    public Deadline resolve(String dueAt, String dueTimeZone, ZoneId creatorZone) {
        return resolve(dueAt, dueTimeZone, creatorZone, "dueAt", "dueTimeZone");
    }

    /**
     * Resolves a deadline, or returns null when both value and zone are absent.
     *
     * @param fallbackZone the zone used when none is given: the creator's directory zone
     * @throws ApiException 422 on the named fields for malformed values, unknown zones, a zone
     *     without a value, and nonexistent or ambiguous local times
     */
    public Deadline resolve(String value, String zoneName, ZoneId fallbackZone, String valueField, String zoneField) {
        boolean hasValue = value != null && !value.isBlank();
        if (!hasValue) {
            if (zoneName != null && !zoneName.isBlank()) {
                throw ApiException.validation(valueField, "REQUIRED", "Enter a deadline for the selected time zone.");
            }
            return null;
        }
        ZoneId zone = zoneName == null || zoneName.isBlank() ? fallbackZone : zone(zoneName, zoneField);
        String text = value.strip();
        try {
            if (text.length() == 10) {
                return new Deadline(localInstant(LocalDate.parse(text).atTime(DATE_ONLY_TIME), zone, valueField), zone);
            }
            if (hasOffset(text)) {
                return new Deadline(OffsetDateTime.parse(text).toInstant(), zone);
            }
            return new Deadline(localInstant(LocalDateTime.parse(text), zone, valueField), zone);
        } catch (DateTimeParseException e) {
            throw ApiException.validation(valueField, "INVALID_FORMAT",
                    "Enter the deadline as a date (YYYY-MM-DD) or a date and time.");
        }
    }

    /** Overdue: open, dated and past the deadline. Done and Cancelled are never overdue (FR-024). */
    public boolean isOverdue(Task task) {
        return task.status().isOpen() && task.deadline() != null && isPast(task.deadline());
    }

    /** Deadline reminders apply to open, dated tasks only, including Blocked ones. */
    public boolean remindersApplicable(Task task) {
        return task.status().isOpen() && task.deadline() != null;
    }

    public boolean isPast(Deadline deadline) {
        return deadline.dueAt().isBefore(clock.instant());
    }

    /** Requires explicit confirmation before a past deadline takes effect on an open task (FR-011). */
    public void requireConfirmationIfPast(Deadline deadline, boolean confirmed) {
        if (deadline != null && !confirmed && isPast(deadline)) {
            throw new ApiException(ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED,
                    "This deadline has already passed. Confirm it to save the task as overdue.");
        }
    }

    private static Instant localInstant(LocalDateTime local, ZoneId zone, String field) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            throw ApiException.validation(field, "NONEXISTENT_LOCAL_TIME",
                    "That time does not exist in the selected time zone because of a daylight saving change.");
        }
        if (offsets.size() > 1) {
            throw ApiException.validation(field, "AMBIGUOUS_LOCAL_TIME",
                    "That time occurs twice in the selected time zone because of a daylight saving change. "
                            + "Choose the earlier or later occurrence.");
        }
        return local.toInstant(offsets.getFirst());
    }

    /** IANA region names only: fixed offsets and abbreviations are not accepted as zones. */
    private static ZoneId zone(String name, String field) {
        try {
            if (ZoneId.getAvailableZoneIds().contains(name)) {
                return ZoneId.of(name);
            }
        } catch (DateTimeException e) {
            // fall through to the validation error
        }
        throw ApiException.validation(field, "UNKNOWN_TIME_ZONE", "Choose a time zone from the list.");
    }

    /** A trailing Z or a +hh:mm / -hh:mm offset after the time part. */
    private static boolean hasOffset(String text) {
        int time = text.indexOf('T');
        return time > 0 && (text.endsWith("Z") || text.indexOf('+', time) > 0 || text.indexOf('-', time) > 0);
    }
}
