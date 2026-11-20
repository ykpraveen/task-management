package com.example.tasks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.tasks.directory.DirectoryRepository;
import com.example.tasks.directory.DirectoryRepository.DirectoryEntry;
import com.example.tasks.domain.ActivityEvent;
import com.example.tasks.domain.Deadline;
import com.example.tasks.domain.DeadlineRequest;
import com.example.tasks.domain.DeadlineRequestState;
import com.example.tasks.domain.Priority;
import com.example.tasks.domain.Task;
import com.example.tasks.domain.TaskStatus;
import com.example.tasks.operations.ApiException;
import com.example.tasks.operations.ProblemCode;
import com.example.tasks.persistence.ActivityEventRepository;
import com.example.tasks.persistence.DeadlineRequestRepository;
import com.example.tasks.persistence.TaskRepository;
import com.example.tasks.service.TaskService.CreateTask;
import com.example.tasks.service.TaskService.DeadlinePatch;
import com.example.tasks.service.TaskService.EditTask;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task, deadline and deadline-request rules without a database: the lifecycle matrix from
 * data-model.md, creator-only edits, deadline resolution (FR-011, FR-025) and every
 * deadline-request transition (FR-020, FR-021).
 *
 * <p>Contract for T036–T041 assumed by this test:
 * <ul>
 *   <li>{@code domain}: records {@code Task}, {@code Deadline(Instant dueAt, ZoneId timeZone)},
 *       {@code DeadlineRequest}, {@code ActivityEvent} with {@code Change(String previous, String next)}
 *       keyed by the migration's allowlisted snake_case field names; enums {@code TaskStatus},
 *       {@code Priority}, {@code DeadlineRequestState}.</li>
 *   <li>{@code persistence}: interfaces {@code TaskRepository}, {@code DeadlineRequestRepository} and
 *       {@code ActivityEventRepository} (JDBC implementations in T037; in-memory fakes here).
 *       {@code insert} returns the stored row with its generated ID.</li>
 *   <li>{@code directory.DirectoryRepository#find(UUID)} returns the active state and IANA zone.</li>
 *   <li>Services throw {@link ApiException}: outsiders get {@code NOT_FOUND}; participants attempting a
 *       disallowed operation get {@code FORBIDDEN}; a stale expected version or stale decision gets
 *       {@code STALE_VERSION}; invalid input gets {@code VALIDATION_FAILED} with DTO field paths.</li>
 *   <li>Every successful mutation, including each deadline-request transition, advances the task
 *       version by one and appends history; a rejected one changes neither.</li>
 * </ul>
 */
class TaskServiceTest {

    static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    static final UUID CREATOR = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    static final UUID ASSIGNEE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    static final UUID REPLACEMENT = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    static final UUID OUTSIDER = UUID.fromString("00000000-0000-4000-8000-00000000000e");
    static final UUID INACTIVE = UUID.fromString("00000000-0000-4000-8000-00000000000f");

    MutableClock clock;
    InMemoryTasks tasks;
    InMemoryRequests requests;
    InMemoryActivity activity;
    DirectoryRepository directory;
    DeadlineService deadlines;
    TaskService service;
    DeadlineRequestService requestService;

    @BeforeEach
    void wire() {
        clock = new MutableClock(NOW);
        tasks = new InMemoryTasks();
        requests = new InMemoryRequests();
        activity = new InMemoryActivity();
        directory = mock(DirectoryRepository.class);
        when(directory.find(any())).thenReturn(Optional.empty());
        user(CREATOR, true, BERLIN);
        user(ASSIGNEE, true, NEW_YORK);
        user(REPLACEMENT, true, ZoneId.of("Asia/Tokyo"));
        user(OUTSIDER, true, BERLIN);
        user(INACTIVE, false, BERLIN);

        deadlines = new DeadlineService(clock);
        service = new TaskService(tasks, requests, activity, directory, deadlines, clock);
        requestService = new DeadlineRequestService(tasks, requests, activity, directory, deadlines, clock);
    }

    // ------------------------------------------------------------------ creation

    @Nested
    class Creation {

        @Test
        void createsATodoTaskWithDefaultsAndHistory() {
            Task task = service.create(CREATOR, new CreateTask("  Prepare the report  ", ASSIGNEE, null, null, null, null, false));

            assertThat(task.id()).isPositive();
            assertThat(task.title()).isEqualTo("Prepare the report");
            assertThat(task.description()).isEmpty();
            assertThat(task.priority()).isEqualTo(Priority.NORMAL);
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.creatorId()).isEqualTo(CREATOR);
            assertThat(task.assigneeId()).isEqualTo(ASSIGNEE);
            assertThat(task.deadline()).isNull();
            assertThat(task.createdAt()).isEqualTo(NOW);
            assertThat(task.version()).isEqualTo(1);
            assertThat(history(task)).extracting(ActivityEvent::type).containsExactly("task_created");
            assertThat(history(task).getFirst().actorId()).isEqualTo(CREATOR);
        }

        @Test
        void selfAssignmentIsAllowed() {
            Task task = service.create(CREATOR, create("Self", CREATOR));
            assertThat(task.assigneeId()).isEqualTo(CREATOR);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "\t"})
        void rejectsBlankTitles(String title) {
            assertValidation(() -> service.create(CREATOR, create(title, ASSIGNEE)), "title");
        }

        @Test
        void rejectsTitlesOver200AndDescriptionsOver20000Characters() {
            service.create(CREATOR, create("t".repeat(200), ASSIGNEE));
            assertValidation(() -> service.create(CREATOR, create("t".repeat(201), ASSIGNEE)), "title");

            service.create(CREATOR, new CreateTask("Long", ASSIGNEE, "d".repeat(20_000), null, null, null, false));
            assertValidation(() -> service.create(CREATOR,
                    new CreateTask("Too long", ASSIGNEE, "d".repeat(20_001), null, null, null, false)), "description");
        }

        @Test
        void rejectsInactiveAndUnknownAssignees() {
            assertValidation(() -> service.create(CREATOR, create("Inactive", INACTIVE)), "assigneeId");
            assertValidation(() -> service.create(CREATOR, create("Unknown", UUID.randomUUID())), "assigneeId");
            assertValidation(() -> service.create(CREATOR, create("Missing", null)), "assigneeId");
            assertThat(tasks.rows).isEmpty();
        }

        @Test
        void outsidersCannotReadAnotherUsersTask() {
            Task task = service.create(CREATOR, create("Private", ASSIGNEE));

            assertThat(service.get(CREATOR, task.id())).isNotNull();
            assertThat(service.get(ASSIGNEE, task.id())).isNotNull();
            assertProblem(() -> service.get(OUTSIDER, task.id()), ProblemCode.NOT_FOUND);
            assertProblem(() -> service.get(CREATOR, task.id() + 1), ProblemCode.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------ lifecycle matrix

    @Nested
    class Lifecycle {

        Task task;

        @BeforeEach
        void openTask() {
            task = service.create(CREATOR, create("Lifecycle", ASSIGNEE));
        }

        @Test
        void eitherParticipantMovesBetweenTodoAndInProgress() {
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.IN_PROGRESS, null);
            assertThat(task.status()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(task.version()).isEqualTo(2);

            task = service.changeStatus(CREATOR, task.id(), task.version(), TaskStatus.TODO, null);
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.version()).isEqualTo(3);

            assertThat(history(task)).extracting(ActivityEvent::type)
                    .containsExactly("task_created", "status_changed", "status_changed");
            assertThat(history(task).get(1).changes().get("status"))
                    .isEqualTo(new ActivityEvent.Change("todo", "in_progress"));
        }

        @Test
        void blockingRequiresAReasonThatLeavingClearsWhileHistoryKeepsIt() {
            assertValidation(() -> service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, null), "reason");
            assertValidation(() -> service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "  "), "reason");
            assertValidation(() -> service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED,
                    "r".repeat(2_001)), "reason");

            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting on supplier");
            assertThat(task.status()).isEqualTo(TaskStatus.BLOCKED);
            assertThat(task.blockedReason()).isEqualTo("Waiting on supplier");

            task = service.changeStatus(CREATOR, task.id(), task.version(), TaskStatus.IN_PROGRESS, null);
            assertThat(task.blockedReason()).isNull();

            ActivityEvent blocked = history(task).get(1);
            assertThat(blocked.changes().get("blocked_reason"))
                    .isEqualTo(new ActivityEvent.Change(null, "Waiting on supplier"));
            assertThat(history(task).getLast().changes().get("blocked_reason"))
                    .isEqualTo(new ActivityEvent.Change("Waiting on supplier", null));
        }

        @Test
        void changingTheBlockedReasonIsRecorded() {
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting on supplier");
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting on legal");

            assertThat(task.blockedReason()).isEqualTo("Waiting on legal");
            assertThat(history(task).getLast().changes().get("blocked_reason"))
                    .isEqualTo(new ActivityEvent.Change("Waiting on supplier", "Waiting on legal"));
        }

        @Test
        void completingRecordsTheCompletionTimeAndReopeningClearsIt() {
            clock.advance(Duration.ofHours(1));
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            assertThat(task.status()).isEqualTo(TaskStatus.DONE);
            assertThat(task.completedAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));

            task = service.reopen(CREATOR, task.id(), task.version(), false);
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.completedAt()).isNull();
            assertThat(history(task).getLast().type()).isEqualTo("task_reopened");
        }

        @Test
        void eitherParticipantMayReopenDone() {
            task = service.changeStatus(CREATOR, task.id(), task.version(), TaskStatus.DONE, null);
            task = service.reopen(ASSIGNEE, task.id(), task.version(), false);
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
        }

        @Test
        void doneAndCancelledRequireReopenFirst() {
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            for (TaskStatus target : List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED, TaskStatus.DONE)) {
                long version = task.version();
                assertProblem(() -> service.changeStatus(ASSIGNEE, task.id(), version, target, "reason"), ProblemCode.FORBIDDEN);
            }

            task = service.reopen(ASSIGNEE, task.id(), task.version(), false);
            task = service.cancel(CREATOR, task.id(), task.version(), "No longer needed");
            for (TaskStatus target : List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED, TaskStatus.DONE)) {
                long version = task.version();
                assertProblem(() -> service.changeStatus(CREATOR, task.id(), version, target, "reason"), ProblemCode.FORBIDDEN);
            }
        }

        @Test
        void theStatusOperationCannotCancel() {
            assertValidation(() -> service.changeStatus(CREATOR, task.id(), task.version(), TaskStatus.CANCELLED, "Use cancel"),
                    "status");
        }

        @Test
        void openTasksCannotBeReopened() {
            assertProblem(() -> service.reopen(CREATOR, task.id(), task.version(), false), ProblemCode.FORBIDDEN);
        }

        @Test
        void outsidersCannotChangeStatus() {
            assertProblem(() -> service.changeStatus(OUTSIDER, task.id(), task.version(), TaskStatus.IN_PROGRESS, null),
                    ProblemCode.NOT_FOUND);
        }

        @Test
        void staleVersionsChangeNothing() {
            long original = task.version();
            task = service.changeStatus(ASSIGNEE, task.id(), original, TaskStatus.IN_PROGRESS, null);
            int events = history(task).size();

            assertProblem(() -> service.changeStatus(CREATOR, task.id(), original, TaskStatus.BLOCKED, "Late"),
                    ProblemCode.STALE_VERSION);

            Task current = service.get(CREATOR, task.id());
            assertThat(current.status()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(current.version()).isEqualTo(task.version());
            assertThat(history(task)).hasSize(events);
        }
    }

    // ------------------------------------------------------------------ cancellation

    @Nested
    class Cancellation {

        Task task;

        @BeforeEach
        void openTask() {
            task = service.create(CREATOR, dated("Cancellable", ASSIGNEE, "2026-12-10"));
        }

        @Test
        void creatorCancelsAnOpenTaskWithAReasonAndKeepsItsContent() {
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting");
            task = service.cancel(CREATOR, task.id(), task.version(), "No longer needed");

            assertThat(task.status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(task.cancellationReason()).isEqualTo("No longer needed");
            assertThat(task.blockedReason()).isNull();
            assertThat(task.completedAt()).isNull();
            assertThat(task.title()).isEqualTo("Cancellable");
            assertThat(task.deadline()).isNotNull();
            assertThat(deadlines.isOverdue(task)).isFalse();
            assertThat(deadlines.remindersApplicable(task)).isFalse();
            assertThat(history(task).getLast().type()).isEqualTo("task_cancelled");
            assertThat(service.get(ASSIGNEE, task.id()).status()).isEqualTo(TaskStatus.CANCELLED);
        }

        @Test
        void requiresANonEmptyReason() {
            assertValidation(() -> service.cancel(CREATOR, task.id(), task.version(), " "), "reason");
            assertValidation(() -> service.cancel(CREATOR, task.id(), task.version(), "r".repeat(2_001)), "reason");
        }

        @Test
        void onlyTheCreatorCancelsAndOnlyOpenTasks() {
            assertProblem(() -> service.cancel(ASSIGNEE, task.id(), task.version(), "Not mine"), ProblemCode.FORBIDDEN);
            assertProblem(() -> service.cancel(OUTSIDER, task.id(), task.version(), "Not mine"), ProblemCode.NOT_FOUND);

            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            assertProblem(() -> service.cancel(CREATOR, task.id(), task.version(), "Too late"), ProblemCode.FORBIDDEN);
        }

        @Test
        void onlyTheCreatorReopensACancelledTaskWhichKeepsItsDeadline() {
            task = service.cancel(CREATOR, task.id(), task.version(), "Paused");
            assertProblem(() -> service.reopen(ASSIGNEE, task.id(), task.version(), false), ProblemCode.FORBIDDEN);

            Deadline deadline = task.deadline();
            task = service.reopen(CREATOR, task.id(), task.version(), false);
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.cancellationReason()).isNull();
            assertThat(task.deadline()).isEqualTo(deadline);
            assertThat(deadlines.remindersApplicable(task)).isTrue();
        }

        @Test
        void supersedesAPendingDeadlineRequest() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            task = current(task);

            task = service.cancel(CREATOR, task.id(), task.version(), "No longer needed");

            assertSuperseded(pending, "cancelled");
        }
    }

    // ------------------------------------------------------------------ creator-only edits

    @Nested
    class Edits {

        Task task;

        @BeforeEach
        void openTask() {
            task = service.create(CREATOR, create("Editable", ASSIGNEE));
        }

        @Test
        void creatorEditsFieldsAndHistoryKeepsPreviousValues() {
            task = service.edit(CREATOR, task.id(), task.version(), EditTask.none()
                    .withTitle("Renamed").withDescription("New details").withPriority(Priority.HIGH));

            assertThat(task.title()).isEqualTo("Renamed");
            assertThat(task.description()).isEqualTo("New details");
            assertThat(task.priority()).isEqualTo(Priority.HIGH);
            assertThat(task.version()).isEqualTo(2);

            ActivityEvent edited = history(task).getLast();
            assertThat(edited.type()).isEqualTo("task_edited");
            assertThat(edited.changes()).containsEntry("title", new ActivityEvent.Change("Editable", "Renamed"))
                    .containsEntry("description", new ActivityEvent.Change("", "New details"))
                    .containsEntry("priority", new ActivityEvent.Change("normal", "high"));
        }

        @Test
        void assigneesCannotEditAnyField() {
            List<EditTask> edits = List.of(
                    EditTask.none().withTitle("Mine now"),
                    EditTask.none().withDescription("Changed"),
                    EditTask.none().withPriority(Priority.LOW),
                    EditTask.none().withAssignee(REPLACEMENT),
                    EditTask.none().withDeadline(new DeadlinePatch.Set("2026-12-01", null)));
            for (EditTask edit : edits) {
                assertProblem(() -> service.edit(ASSIGNEE, task.id(), task.version(), edit), ProblemCode.FORBIDDEN);
            }
            assertProblem(() -> service.edit(OUTSIDER, task.id(), task.version(), EditTask.none().withTitle("x")),
                    ProblemCode.NOT_FOUND);
            assertThat(current(task).version()).isEqualTo(1);
        }

        @Test
        void reassignmentRequiresAnActiveUserAndMovesAccess() {
            assertValidation(() -> service.edit(CREATOR, task.id(), task.version(), EditTask.none().withAssignee(INACTIVE)),
                    "assigneeId");

            task = service.edit(CREATOR, task.id(), task.version(), EditTask.none().withAssignee(REPLACEMENT));

            assertThat(task.assigneeId()).isEqualTo(REPLACEMENT);
            assertThat(history(task).getLast().type()).isEqualTo("task_reassigned");
            assertThat(history(task).getLast().changes().get("assignee_id"))
                    .isEqualTo(new ActivityEvent.Change(ASSIGNEE.toString(), REPLACEMENT.toString()));
            assertProblem(() -> service.get(ASSIGNEE, task.id()), ProblemCode.NOT_FOUND);
            assertThat(service.get(REPLACEMENT, task.id()).title()).isEqualTo("Editable");
        }

        @Test
        void reassignmentSupersedesTheFormerAssigneesRequest() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            task = current(task);

            service.edit(CREATOR, task.id(), task.version(), EditTask.none().withAssignee(REPLACEMENT));

            assertSuperseded(pending, "reassigned");
        }

        @Test
        void validatesEditedValues() {
            assertValidation(() -> service.edit(CREATOR, task.id(), task.version(), EditTask.none().withTitle(" ")), "title");
            assertValidation(() -> service.edit(CREATOR, task.id(), task.version(),
                    EditTask.none().withDescription("d".repeat(20_001))), "description");
        }

        @Test
        void staleEditsFail() {
            long original = task.version();
            task = service.edit(CREATOR, task.id(), original, EditTask.none().withTitle("First"));

            assertProblem(() -> service.edit(CREATOR, task.id(), original, EditTask.none().withTitle("Second")),
                    ProblemCode.STALE_VERSION);
            assertThat(current(task).title()).isEqualTo("First");
        }
    }

    // ------------------------------------------------------------------ deadlines

    @Nested
    class Deadlines {

        @Test
        void dateOnlyResolvesTo2359InTheCreatorsDirectoryZone() {
            Task task = service.create(CREATOR, dated("Berlin date", ASSIGNEE, "2026-12-01"));

            assertThat(task.deadline()).isEqualTo(new Deadline(Instant.parse("2026-12-01T22:59:00Z"), BERLIN));
        }

        @Test
        void dateOnlyUsesAnExplicitZoneWhenGiven() {
            Task task = service.create(CREATOR, new CreateTask("Tokyo date", ASSIGNEE, null, null,
                    "2026-12-01", "Asia/Tokyo", false));

            assertThat(task.deadline()).isEqualTo(new Deadline(Instant.parse("2026-12-01T14:59:00Z"), ZoneId.of("Asia/Tokyo")));
        }

        @Test
        void dateOnlyFollowsTheCreatorsCurrentZoneNotTheAssignees() {
            user(CREATOR, true, NEW_YORK);
            Task task = service.create(CREATOR, dated("Moved creator", ASSIGNEE, "2026-12-01"));

            assertThat(task.deadline()).isEqualTo(new Deadline(Instant.parse("2026-12-02T04:59:00Z"), NEW_YORK));
        }

        @Test
        void dateOnlyOnDstTransitionDaysUsesThatDaysOffset() {
            // Berlin springs forward on 2027-03-28 and falls back on 2027-10-31.
            assertThat(service.create(CREATOR, dated("Spring", ASSIGNEE, "2027-03-28")).deadline().dueAt())
                    .isEqualTo(Instant.parse("2027-03-28T21:59:00Z"));
            assertThat(service.create(CREATOR, dated("Autumn", ASSIGNEE, "2027-10-31")).deadline().dueAt())
                    .isEqualTo(Instant.parse("2027-10-31T22:59:00Z"));
        }

        @Test
        void rejectsALocalTimeInADstGap() {
            assertValidation(() -> service.create(CREATOR, new CreateTask("Gap", ASSIGNEE, null, null,
                    "2027-03-28T02:30", "Europe/Berlin", false)), "dueAt");
            assertValidation(() -> service.create(CREATOR, new CreateTask("Gap", ASSIGNEE, null, null,
                    "2027-03-14T02:30", "America/New_York", false)), "dueAt");
        }

        @Test
        void rejectsAnAmbiguousLocalTimeInADstOverlapUnlessAnOffsetIsGiven() {
            assertValidation(() -> service.create(CREATOR, new CreateTask("Overlap", ASSIGNEE, null, null,
                    "2027-10-31T02:30", "Europe/Berlin", false)), "dueAt");

            Task first = service.create(CREATOR, new CreateTask("Earlier", ASSIGNEE, null, null,
                    "2027-10-31T02:30+02:00", "Europe/Berlin", false));
            Task second = service.create(CREATOR, new CreateTask("Later", ASSIGNEE, null, null,
                    "2027-10-31T02:30+01:00", "Europe/Berlin", false));
            assertThat(first.deadline().dueAt()).isEqualTo(Instant.parse("2027-10-31T00:30:00Z"));
            assertThat(second.deadline().dueAt()).isEqualTo(Instant.parse("2027-10-31T01:30:00Z"));
        }

        @Test
        void acceptsAnUnambiguousLocalTime() {
            Task task = service.create(CREATOR, new CreateTask("Afternoon", ASSIGNEE, null, null,
                    "2026-12-01T17:00", "America/New_York", false));
            assertThat(task.deadline()).isEqualTo(new Deadline(Instant.parse("2026-12-01T22:00:00Z"), NEW_YORK));
        }

        @Test
        void rejectsMalformedValuesUnknownZonesAndAZoneWithoutADeadline() {
            assertValidation(() -> service.create(CREATOR, new CreateTask("Bad", ASSIGNEE, null, null,
                    "next tuesday", null, false)), "dueAt");
            assertValidation(() -> service.create(CREATOR, new CreateTask("Bad", ASSIGNEE, null, null,
                    "2026-12-01", "Mars/Olympus_Mons", false)), "dueTimeZone");
            assertValidation(() -> service.create(CREATOR, new CreateTask("Bad", ASSIGNEE, null, null,
                    null, "Europe/Berlin", false)), "dueAt");
        }

        @Test
        void overdueAppliesOnlyToOpenTasksPastTheirDeadline() {
            Task task = service.create(CREATOR, new CreateTask("Soon", ASSIGNEE, null, null,
                    "2026-11-20T12:00Z", "Europe/Berlin", false));
            assertThat(deadlines.isOverdue(task)).isFalse();

            clock.advance(Duration.ofHours(4));
            assertThat(deadlines.isOverdue(task)).isTrue();

            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting");
            assertThat(deadlines.isOverdue(task)).isTrue();
            assertThat(deadlines.remindersApplicable(task)).isTrue();

            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            assertThat(deadlines.isOverdue(task)).isFalse();
            assertThat(deadlines.remindersApplicable(task)).isFalse();
        }

        @Test
        void selfAssignedCreatorsEditTheDeadlineDirectly() {
            Task task = service.create(CREATOR, create("Self", CREATOR));

            task = service.edit(CREATOR, task.id(), task.version(),
                    EditTask.none().withDeadline(new DeadlinePatch.Set("2026-12-05", null)));

            assertThat(task.deadline()).isEqualTo(new Deadline(Instant.parse("2026-12-05T22:59:00Z"), BERLIN));
            assertThat(history(task).getLast().changes()).containsKeys("due_at", "due_time_zone");
        }

        @Test
        void removingADeadlineClearsOverdueAndRemindersAndSupersedesTheRequest() {
            Task task = service.create(CREATOR, dated("Dated", ASSIGNEE, "2026-11-25"));
            DeadlineRequest pending = submit(task, "2026-12-20");
            task = current(task);
            clock.advance(Duration.ofDays(10));
            assertThat(deadlines.isOverdue(task)).isTrue();

            task = service.edit(CREATOR, task.id(), task.version(), EditTask.none().withDeadline(new DeadlinePatch.Remove()));

            assertThat(task.deadline()).isNull();
            assertThat(deadlines.isOverdue(task)).isFalse();
            assertThat(deadlines.remindersApplicable(task)).isFalse();
            assertSuperseded(pending, "deadline_removed");
            ActivityEvent edited = history(task).getLast();
            assertThat(edited.type()).isEqualTo("task_edited");
            assertThat(edited.changes().get("due_at").next()).isNull();
            assertThat(edited.changes().get("due_at").previous()).isEqualTo("2026-11-25T22:59:00Z");
        }

        @Test
        void changingTheDeadlineDirectlySupersedesTheRequest() {
            Task task = service.create(CREATOR, dated("Dated", ASSIGNEE, "2026-12-10"));
            DeadlineRequest pending = submit(task, "2026-12-20");
            task = current(task);

            service.edit(CREATOR, task.id(), task.version(),
                    EditTask.none().withDeadline(new DeadlinePatch.Set("2026-12-15", null)));

            assertSuperseded(pending, "deadline_changed");
        }

        @Test
        void editsThatLeaveTheDeadlineAloneKeepThePendingRequest() {
            Task task = service.create(CREATOR, dated("Dated", ASSIGNEE, "2026-12-10"));
            DeadlineRequest pending = submit(task, "2026-12-20");
            task = current(task);

            service.edit(CREATOR, task.id(), task.version(), EditTask.none().withTitle("Renamed"));

            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.PENDING);
        }
    }

    // ------------------------------------------------------------------ past-deadline confirmation (FR-011)

    @Nested
    class PastDeadlineConfirmation {

        @Test
        void creatingWithAPastDeadlineRequiresConfirmation() {
            assertProblem(() -> service.create(CREATOR, dated("Late", ASSIGNEE, "2026-11-19")),
                    ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED);
            assertThat(tasks.rows).isEmpty();

            Task task = service.create(CREATOR, new CreateTask("Late", ASSIGNEE, null, null, "2026-11-19", null, true));
            assertThat(deadlines.isOverdue(task)).isTrue();
        }

        @Test
        void editingToAPastDeadlineRequiresConfirmation() {
            Task task = service.create(CREATOR, create("Edit", ASSIGNEE));
            EditTask past = EditTask.none().withDeadline(new DeadlinePatch.Set("2026-11-01", null));

            assertProblem(() -> service.edit(CREATOR, task.id(), task.version(), past),
                    ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED);
            assertThat(current(task).deadline()).isNull();

            assertThat(service.edit(CREATOR, task.id(), task.version(), past.confirmingPastDeadline()).deadline()).isNotNull();
        }

        @Test
        void editingOtherFieldsOfAnOverdueTaskNeedsNoConfirmation() {
            Task task = service.create(CREATOR, dated("Overdue", ASSIGNEE, "2026-11-21"));
            clock.advance(Duration.ofDays(5));

            Task edited = service.edit(CREATOR, task.id(), task.version(), EditTask.none().withTitle("Still overdue"));
            assertThat(edited.title()).isEqualTo("Still overdue");
        }

        @Test
        void reopeningADoneTaskWhoseDeadlinePassedRequiresConfirmation() {
            Task task = service.create(CREATOR, dated("Reopen", ASSIGNEE, "2026-11-21"));
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            clock.advance(Duration.ofDays(5));
            long version = task.version();
            long id = task.id();

            assertProblem(() -> service.reopen(ASSIGNEE, id, version, false), ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED);
            assertThat(current(task).status()).isEqualTo(TaskStatus.DONE);

            assertThat(service.reopen(ASSIGNEE, id, version, true).status()).isEqualTo(TaskStatus.TODO);
        }

        @Test
        void reopeningACancelledTaskWhoseDeadlinePassedRequiresConfirmation() {
            Task task = service.create(CREATOR, dated("Reopen", ASSIGNEE, "2026-11-21"));
            task = service.cancel(CREATOR, task.id(), task.version(), "Paused");
            clock.advance(Duration.ofDays(5));
            long version = task.version();
            long id = task.id();

            assertProblem(() -> service.reopen(CREATOR, id, version, false), ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED);
            assertThat(service.reopen(CREATOR, id, version, true).status()).isEqualTo(TaskStatus.TODO);
        }

        @Test
        void reopeningAnUndatedTaskNeedsNoConfirmation() {
            Task task = service.create(CREATOR, create("Undated", ASSIGNEE));
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);

            assertThat(service.reopen(ASSIGNEE, task.id(), task.version(), false).status()).isEqualTo(TaskStatus.TODO);
        }

        @Test
        void approvingAProposalThatHasSincePassedRequiresConfirmation() {
            Task task = service.create(CREATOR, dated("Approve", ASSIGNEE, "2026-11-22"));
            DeadlineRequest pending = submit(task, "2026-11-24");
            clock.advance(Duration.ofDays(5));
            long version = current(task).version();

            assertProblem(() -> requestService.approve(CREATOR, task.id(), pending.id(), version, false),
                    ProblemCode.PAST_DEADLINE_CONFIRMATION_REQUIRED);
            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.PENDING);

            requestService.approve(CREATOR, task.id(), pending.id(), version, true);
            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.APPROVED);
        }
    }

    // ------------------------------------------------------------------ deadline-request transitions

    @Nested
    class DeadlineRequests {

        Task task;

        @BeforeEach
        void datedTask() {
            task = service.create(CREATOR, dated("Requested", ASSIGNEE, "2026-12-10"));
        }

        @Test
        void assigneeSubmitsAPendingRequestThatLeavesTheDeadlineInPlace() {
            Deadline before = task.deadline();
            DeadlineRequest pending = requestService.submit(ASSIGNEE, task.id(), task.version(),
                    "2026-12-20", "America/New_York", "Supplier delay");

            assertThat(pending.id()).isPositive();
            assertThat(pending.state()).isEqualTo(DeadlineRequestState.PENDING);
            assertThat(pending.requesterId()).isEqualTo(ASSIGNEE);
            assertThat(pending.proposed()).isEqualTo(new Deadline(Instant.parse("2026-12-21T04:59:00Z"), NEW_YORK));
            assertThat(pending.reason()).isEqualTo("Supplier delay");
            Task after = current(task);
            assertThat(after.deadline()).isEqualTo(before);
            assertThat(after.version()).isEqualTo(task.version() + 1);
            assertThat(history(task).getLast().type()).isEqualTo("deadline_request_submitted");
            assertThat(history(task).getLast().deadlineRequestId()).isEqualTo(pending.id());
        }

        @Test
        void undatedTasksAcceptAProposedDate() {
            Task undated = service.create(CREATOR, create("Undated", ASSIGNEE));
            DeadlineRequest pending = submit(undated, "2026-12-20");
            assertThat(pending.state()).isEqualTo(DeadlineRequestState.PENDING);
        }

        @Test
        void onlyADistinctAssigneeOfAnOpenTaskMaySubmit() {
            assertProblem(() -> requestService.submit(CREATOR, task.id(), task.version(), "2026-12-20", null, "Mine"),
                    ProblemCode.FORBIDDEN);
            assertProblem(() -> requestService.submit(OUTSIDER, task.id(), task.version(), "2026-12-20", null, "Mine"),
                    ProblemCode.NOT_FOUND);

            Task self = service.create(CREATOR, dated("Self", CREATOR, "2026-12-10"));
            assertProblem(() -> requestService.submit(CREATOR, self.id(), self.version(), "2026-12-20", null, "Self"),
                    ProblemCode.FORBIDDEN);

            Task done = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.DONE, null);
            assertProblem(() -> requestService.submit(ASSIGNEE, done.id(), done.version(), "2026-12-20", null, "Late"),
                    ProblemCode.FORBIDDEN);
        }

        @Test
        void blockedTasksAreOpenAndAcceptRequests() {
            task = service.changeStatus(ASSIGNEE, task.id(), task.version(), TaskStatus.BLOCKED, "Waiting");
            assertThat(submit(task, "2026-12-20").state()).isEqualTo(DeadlineRequestState.PENDING);
        }

        @Test
        void validatesTheProposal() {
            assertValidation(() -> requestService.submit(ASSIGNEE, task.id(), task.version(), "2026-12-10", "Europe/Berlin",
                    "Same date"), "proposedDueAt");
            assertValidation(() -> requestService.submit(ASSIGNEE, task.id(), task.version(), null, null, "No date"),
                    "proposedDueAt");
            assertValidation(() -> requestService.submit(ASSIGNEE, task.id(), task.version(), "2027-03-28T02:30",
                    "Europe/Berlin", "Gap"), "proposedDueAt");
            assertValidation(() -> requestService.submit(ASSIGNEE, task.id(), task.version(), "2026-12-20", null, " "),
                    "reason");
            assertThat(requests.rows).isEmpty();
        }

        @Test
        void allowsOnlyOnePendingRequestPerTask() {
            submit(task, "2026-12-20");
            Task updated = current(task);

            assertProblem(() -> requestService.submit(ASSIGNEE, updated.id(), updated.version(), "2026-12-24", null, "Again"),
                    ProblemCode.FORBIDDEN);
        }

        @Test
        void creatorApprovesAndTheProposalBecomesTheDeadline() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            Task before = current(task);

            DeadlineRequest approved = requestService.approve(CREATOR, task.id(), pending.id(), before.version(), false);

            assertThat(approved.state()).isEqualTo(DeadlineRequestState.APPROVED);
            assertThat(approved.resolvedBy()).isEqualTo(CREATOR);
            assertThat(approved.resolvedAt()).isEqualTo(NOW);
            Task after = current(task);
            assertThat(after.deadline()).isEqualTo(pending.proposed());
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(history(task).getLast().type()).isEqualTo("deadline_request_approved");
            assertThat(history(task).getLast().changes()).containsKey("due_at");
        }

        @Test
        void creatorDeclinesAndTheDeadlineStays() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            Task before = current(task);

            DeadlineRequest declined = requestService.decline(CREATOR, task.id(), pending.id(), before.version());

            assertThat(declined.state()).isEqualTo(DeadlineRequestState.DECLINED);
            assertThat(current(task).deadline()).isEqualTo(task.deadline());
            assertThat(history(task).getLast().type()).isEqualTo("deadline_request_declined");
        }

        @Test
        void requesterWithdrawsAndMaySubmitAgain() {
            DeadlineRequest pending = submit(task, "2026-12-20");

            DeadlineRequest withdrawn = requestService.withdraw(ASSIGNEE, task.id(), pending.id(), current(task).version());

            assertThat(withdrawn.state()).isEqualTo(DeadlineRequestState.WITHDRAWN);
            assertThat(withdrawn.resolvedBy()).isEqualTo(ASSIGNEE);
            assertThat(history(task).getLast().type()).isEqualTo("deadline_request_withdrawn");
            assertThat(submit(current(task), "2026-12-22").state()).isEqualTo(DeadlineRequestState.PENDING);
        }

        @Test
        void onlyTheCreatorDecidesAndOnlyTheRequesterWithdraws() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            long version = current(task).version();

            assertProblem(() -> requestService.approve(ASSIGNEE, task.id(), pending.id(), version, false), ProblemCode.FORBIDDEN);
            assertProblem(() -> requestService.decline(ASSIGNEE, task.id(), pending.id(), version), ProblemCode.FORBIDDEN);
            assertProblem(() -> requestService.withdraw(CREATOR, task.id(), pending.id(), version), ProblemCode.FORBIDDEN);
            assertProblem(() -> requestService.approve(OUTSIDER, task.id(), pending.id(), version, false), ProblemCode.NOT_FOUND);
            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.PENDING);
        }

        @Test
        void requestIdsOfAnotherTaskAreNotFound() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            Task other = service.create(CREATOR, dated("Other", ASSIGNEE, "2026-12-10"));

            assertProblem(() -> requestService.approve(CREATOR, other.id(), pending.id(), other.version(), false),
                    ProblemCode.NOT_FOUND);
        }

        @Test
        void staleDecisionsFailWithoutChangingTheTask() {
            DeadlineRequest pending = submit(task, "2026-12-20");
            long version = current(task).version();
            requestService.withdraw(ASSIGNEE, task.id(), pending.id(), version);
            Task afterWithdrawal = current(task);

            // The creator decides from a page loaded before the withdrawal.
            assertProblem(() -> requestService.approve(CREATOR, task.id(), pending.id(), version, false),
                    ProblemCode.STALE_VERSION);
            // Even with the current version, a resolved request cannot be decided again.
            assertProblem(() -> requestService.approve(CREATOR, task.id(), pending.id(), afterWithdrawal.version(), false),
                    ProblemCode.STALE_VERSION);
            assertProblem(() -> requestService.decline(CREATOR, task.id(), pending.id(), afterWithdrawal.version()),
                    ProblemCode.STALE_VERSION);

            assertThat(current(task).deadline()).isEqualTo(task.deadline());
            assertThat(current(task).version()).isEqualTo(afterWithdrawal.version());
            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.WITHDRAWN);
        }

        @Test
        void completionSupersedesThePendingRequest() {
            DeadlineRequest pending = submit(task, "2026-12-20");

            service.changeStatus(ASSIGNEE, task.id(), current(task).version(), TaskStatus.DONE, null);

            assertSuperseded(pending, "completed");
        }

        @Test
        void blockingKeepsThePendingRequest() {
            DeadlineRequest pending = submit(task, "2026-12-20");

            service.changeStatus(ASSIGNEE, task.id(), current(task).version(), TaskStatus.BLOCKED, "Waiting");

            assertThat(requests.byId(pending.id()).state()).isEqualTo(DeadlineRequestState.PENDING);
        }

        @Test
        void retainsEveryRequestState() {
            DeadlineRequest declined = submit(task, "2026-12-20");
            requestService.decline(CREATOR, task.id(), declined.id(), current(task).version());
            DeadlineRequest withdrawn = submit(current(task), "2026-12-21");
            requestService.withdraw(ASSIGNEE, task.id(), withdrawn.id(), current(task).version());
            DeadlineRequest approved = submit(current(task), "2026-12-22");
            requestService.approve(CREATOR, task.id(), approved.id(), current(task).version(), false);
            DeadlineRequest superseded = submit(current(task), "2026-12-23");
            service.cancel(CREATOR, task.id(), current(task).version(), "Stopped");

            assertThat(requests.listByTask(task.id())).extracting(DeadlineRequest::state).containsExactly(
                    DeadlineRequestState.DECLINED, DeadlineRequestState.WITHDRAWN,
                    DeadlineRequestState.APPROVED, DeadlineRequestState.SUPERSEDED);
            assertThat(history(task)).extracting(ActivityEvent::type).contains(
                    "deadline_request_submitted", "deadline_request_declined", "deadline_request_withdrawn",
                    "deadline_request_approved", "deadline_request_superseded", "task_cancelled");
            assertThat(superseded.id()).isPositive();
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"TODO", "IN_PROGRESS", "BLOCKED"})
    void openStatusesAreOpen(TaskStatus status) {
        assertThat(status.isOpen()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"DONE", "CANCELLED"})
    void doneAndCancelledAreClosed(TaskStatus status) {
        assertThat(status.isOpen()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    void user(UUID id, boolean active, ZoneId zone) {
        when(directory.find(id)).thenReturn(Optional.of(
                new DirectoryEntry(id, "User " + id.toString().substring(32), id + "@demo.invalid", active, zone)));
    }

    static CreateTask create(String title, UUID assignee) {
        return new CreateTask(title, assignee, null, null, null, null, false);
    }

    static CreateTask dated(String title, UUID assignee, String date) {
        return new CreateTask(title, assignee, null, null, date, null, false);
    }

    DeadlineRequest submit(Task task, String date) {
        Task latest = current(task);
        return requestService.submit(latest.assigneeId(), latest.id(), latest.version(), date, null, "Need more time");
    }

    Task current(Task task) {
        return tasks.byId(task.id());
    }

    List<ActivityEvent> history(Task task) {
        return activity.rows.stream().filter(e -> e.taskId().equals(task.id())).toList();
    }

    void assertSuperseded(DeadlineRequest request, String cause) {
        DeadlineRequest stored = requests.byId(request.id());
        assertThat(stored.state()).isEqualTo(DeadlineRequestState.SUPERSEDED);
        assertThat(stored.resolutionCause()).isEqualTo(cause);
        assertThat(stored.resolvedAt()).isNotNull();
        assertThat(activity.rows).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo("deadline_request_superseded");
            assertThat(e.deadlineRequestId()).isEqualTo(request.id());
        });
    }

    static void assertProblem(Runnable call, ProblemCode code) {
        ApiException thrown = catchThrowableOfType(ApiException.class, call::run);
        assertThat(thrown).as("expected %s", code).isNotNull();
        assertThat(thrown.code()).isEqualTo(code);
    }

    static void assertValidation(Runnable call, String field) {
        ApiException thrown = catchThrowableOfType(ApiException.class, call::run);
        assertThat(thrown).as("expected a validation failure on %s", field).isNotNull();
        assertThat(thrown.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
        assertThat(thrown.fieldErrors()).extracting(ApiException.FieldError::field).contains(field);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class InMemoryTasks implements TaskRepository {
        final Map<Long, Task> rows = new HashMap<>();
        final AtomicLong ids = new AtomicLong(100);

        @Override
        public Task insert(Task task) {
            Task stored = task.withId(ids.incrementAndGet());
            rows.put(stored.id(), stored);
            return stored;
        }

        @Override
        public Optional<Task> find(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Task> findForUpdate(long id) {
            return find(id);
        }

        @Override
        public void update(Task task) {
            Task previous = rows.get(task.id());
            assertThat(previous).isNotNull();
            assertThat(task.version()).as("version advances by one").isEqualTo(previous.version() + 1);
            assertThat(task.creatorId()).as("creator is immutable").isEqualTo(previous.creatorId());
            rows.put(task.id(), task);
        }

        Task byId(long id) {
            return rows.get(id);
        }
    }

    static final class InMemoryRequests implements DeadlineRequestRepository {
        final Map<Long, DeadlineRequest> rows = new HashMap<>();
        final AtomicLong ids = new AtomicLong(500);

        @Override
        public DeadlineRequest insert(DeadlineRequest request) {
            assertThat(findPending(request.taskId())).as("one pending request per task").isEmpty();
            DeadlineRequest stored = request.withId(ids.incrementAndGet());
            rows.put(stored.id(), stored);
            return stored;
        }

        @Override
        public Optional<DeadlineRequest> find(long id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<DeadlineRequest> findPending(long taskId) {
            return rows.values().stream()
                    .filter(r -> r.taskId().equals(taskId) && r.state() == DeadlineRequestState.PENDING)
                    .findFirst();
        }

        @Override
        public void update(DeadlineRequest request) {
            DeadlineRequest previous = rows.get(request.id());
            assertThat(previous.state()).as("resolved requests are immutable").isEqualTo(DeadlineRequestState.PENDING);
            rows.put(request.id(), request);
        }

        @Override
        public List<DeadlineRequest> listByTask(long taskId) {
            return rows.values().stream().filter(r -> r.taskId().equals(taskId))
                    .sorted(Comparator.comparing(DeadlineRequest::id)).toList();
        }

        DeadlineRequest byId(long id) {
            return rows.get(id);
        }
    }

    static final class InMemoryActivity implements ActivityEventRepository {
        final List<ActivityEvent> rows = new ArrayList<>();
        final AtomicLong ids = new AtomicLong(900);

        @Override
        public ActivityEvent append(ActivityEvent event) {
            ActivityEvent stored = event.withId(ids.incrementAndGet());
            rows.add(stored);
            return stored;
        }
    }
}
