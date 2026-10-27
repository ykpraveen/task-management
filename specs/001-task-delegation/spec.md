# Feature Specification: Private Task Delegation

**Feature Branch**: No feature branch created by this specification workflow.

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "build a task management tool, where user can create a task for another user, add comments, attachments, set to be completed by. explore other ideas with me"

## Clarifications

### Session 2026-10-27

- Q: How should employees get access to the tool? A: Supabase provides sign-in and separate user management; a logged-in user needs no additional task-management enrollment and can search other users to assign work.
- Q: What should happen when an assignee cannot meet the deadline or is waiting on someone else? A: Add Blocked with a required reason and allow the assignee to request a deadline change that the creator approves or declines.
- Q: How should employees receive task updates and reminders when they are not using the tool? A: All updates remain in-app; prepare optional email for assignments, deadline-change requests and decisions, and deadline reminders, with delivery decoupled so the sending method can change or sending can be disabled.
- Q: What should happen when a task is no longer needed? A: Only the creator can cancel it with a reason or reopen it later; retain content and history and stop reminders while cancelled.
- Q: Should every task require a deadline, or should employees also be able to assign work without one? A: Deadlines are optional; undated tasks remain in personal lists and have no deadline reminders or overdue status until a date is set.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Delegate and Complete a Task (Priority: P1)

An employee assigns work to a colleague with a clear title, optional description, priority, and optional deadline. The assignee finds the work in their personal list, records progress, and marks it done. Only the creator and current assignee can access the task.

**Why this priority**: Clear ownership and completion are the core value of delegated work.

**Independent Test**: Using three employee accounts, create a task for a colleague, progress it through its statuses, and confirm the uninvolved employee cannot discover or access it.

**Acceptance Scenarios**:

1. **Given** a signed-in employee and an active colleague, **When** the employee submits a title, assignee, and completion date, **Then** a private task is created in To do status and appears in the creator's Created by me list and the assignee's Assigned to me list.
2. **Given** an assigned task, **When** the assignee changes its status to In progress and then Done, **Then** both participants see the new status and the completion time.
3. **Given** a task, **When** an uninvolved employee searches for it or attempts to open its details, comments, attachments, or history directly, **Then** access is denied without exposing task content.
4. **Given** an existing task, **When** its creator changes the description, priority, or deadline, **Then** both participants see the updated values and an activity entry records the change.
5. **Given** a completed task, **When** either participant reopens it, **Then** its status becomes To do, it no longer has a current completion time, and the earlier completion remains in history.
6. **Given** a task assigned to a colleague, **When** its creator reassigns it to another active colleague, **Then** the new assignee gains access, the former assignee loses access unless they are the creator, and ownership history is retained.
7. **Given** a signed-in employee, **When** they search for another user during assignment, **Then** matching eligible users are shown with enough identity information to distinguish them, without exposing their tasks.
8. **Given** an open task, **When** either participant marks it Blocked and supplies a nonempty reason, **Then** the reason appears with its status and in history, and a dated task remains subject to deadline reminders.
9. **Given** a task assigned to a colleague, **When** the assignee requests a different deadline with a reason, **Then** the creator is notified and the existing deadline remains effective until the creator approves the request.
10. **Given** a pending deadline-change request, **When** the creator approves it, **Then** the requested deadline becomes effective, reminder scheduling is updated, the decision is recorded, and the assignee is notified; declining records the decision and notifies the assignee without changing the deadline.
11. **Given** an open task, **When** the creator cancels it with a nonempty reason, **Then** it becomes Cancelled, its content remains accessible to its participants, reminders stop, any pending deadline request is superseded, and the assignee receives an in-app notification unless they are also the creator.
12. **Given** a Cancelled task, **When** its creator reopens it, **Then** it returns to To do with its existing deadline and retained history; the assignee cannot reopen or otherwise change its status while it is Cancelled.
13. **Given** a signed-in employee, **When** they create an assigned task without a deadline, **Then** it appears in both applicable personal lists as No deadline and receives no deadline reminders or overdue label.
14. **Given** a dated task, **When** the creator removes its deadline, **Then** pending deadline reminders are suppressed, its overdue label disappears, any pending deadline-change request is superseded, and the change is recorded and notified in-app.

---

### User Story 2 - Discuss Work and Share Files (Priority: P2)

The creator and assignee discuss the task in its comments and attach supporting files so that instructions and evidence remain with the work.

**Why this priority**: A shared discussion and supporting files reduce fragmented conversations and missing context.

**Independent Test**: Create a task, exchange comments from both participants, upload and download a file, and verify a third account cannot access either the discussion or the file.

**Acceptance Scenarios**:

1. **Given** a task accessible to both participants, **When** either adds a nonempty comment, **Then** both see its author, posting time, and text in chronological order.
2. **Given** a task, **When** a participant uploads a permitted file, **Then** both participants can see its name, size, uploader, and upload time and download the original file.
3. **Given** an unsupported or oversized file, **When** a participant attempts to upload it, **Then** a specific rejection explains the allowed formats or size limit and no attachment is shown as successfully uploaded.
4. **Given** a failed comment submission or file upload, **When** the operation fails, **Then** the participant sees an actionable error, retains entered comment text where applicable, and can retry without a duplicate successful submission.

---

### User Story 3 - Find and Prioritize Personal Work (Priority: P2)

An employee reviews assigned work or tasks they created and locates what needs attention using search, filters, deadline sorting, and overdue indicators.

**Why this priority**: Delegation is useful only when employees can reliably find and prioritize their work.

**Independent Test**: Populate both personal lists with tasks of different priorities, statuses, and deadlines, then verify each search, filter, and sort against the expected task set.

**Acceptance Scenarios**:

1. **Given** several accessible tasks, **When** an employee opens Assigned to me or Created by me, **Then** the appropriate list shows title, creator, assignee, priority, status, and deadline.
2. **Given** a personal task list, **When** the employee combines title/description search with status, priority, assignee, and due-date filters, **Then** only tasks satisfying all selected conditions appear.
3. **Given** an open task whose deadline has passed, **When** either participant views it in a list or its details, **Then** it is labeled overdue; Done, Cancelled, and undated tasks are not labeled overdue.
4. **Given** a task list, **When** the employee sorts by deadline, **Then** dated tasks are ordered by deadline with a stable task-creation-order tie break and undated tasks appear last in either direction.
5. **Given** no matching tasks, **When** the employee searches or filters, **Then** an empty-state message distinguishes no matches from a failure to load tasks.

---

### User Story 4 - Stay Informed Without Chasing Updates (Priority: P3)

Employees receive private in-app notifications for assignments, relevant changes, and approaching deadlines. A task's activity history explains what changed and who changed it.

**Why this priority**: Timely prompts and a reliable history reduce manual follow-up.

**Independent Test**: Assign a task, change its deadline and status, add a comment and file, and advance time across reminder boundaries; verify recipients, history, and duplicate prevention.

**Acceptance Scenarios**:

1. **Given** a new or reassigned task, **When** assignment succeeds, **Then** the current assignee receives an in-app notification linking to the task unless they performed the assignment themselves.
2. **Given** a task, **When** one participant comments, attaches a file, changes its deadline, or changes its status, **Then** the other current participant receives an in-app notification without notifying the actor about their own action.
3. **Given** an open task with a deadline more than 24 hours away, **When** it reaches 24 hours before the deadline, **Then** the current assignee receives one due-soon reminder for that deadline.
4. **Given** an open task with a deadline, **When** its deadline passes, **Then** its current participants receive one overdue notification each for that deadline.
5. **Given** a task's activity history, **When** a participant reviews it, **Then** creation, reassignment, field changes, status changes, comments, and attachment additions show their actor and time in chronological order.
6. **Given** a former assignee, **When** they view an old notification after reassignment, **Then** it reveals no task content and cannot restore access to the task.
7. **Given** email delivery is enabled, **When** an assignment, deadline-change request or decision, due-soon reminder, or overdue alert occurs, **Then** the eligible recipient receives a generic email with a sign-in-protected task link and no task title, description, comment, filename, or blocked/request reason.
8. **Given** email delivery is disabled or fails, **When** a task action succeeds, **Then** its in-app notification and task changes remain available; failed email delivery is visible to operators and disabling delivery does not cause a backlog of old emails to be sent upon re-enabling.

### Edge Cases

- Missing or whitespace-only titles, invalid supplied dates, and inactive assignees are rejected with field-specific explanations. An absent deadline is valid.
- A creator may assign work to themselves; personal lists may both contain that task. Actions do not notify their own actor, and scheduled reminders go to that person only once per reminder type and deadline.
- Creating or rescheduling an open task with a past deadline requires explicit confirmation; the task is immediately overdue and receives one overdue notification per current participant.
- A date without a time means 23:59 on that date in the creator's selected time zone. Daylight-saving changes must not cause reminders or overdue indicators to disagree with the displayed deadline.
- A task created or rescheduled less than 24 hours before its future deadline receives one immediate due-soon reminder instead of missing the reminder window.
- Completion or cancellation suppresses pending deadline reminders. A changed deadline cancels pending reminders for the old deadline and reevaluates scheduling for the new deadline without deleting delivery records. Restoring a previously used deadline or reopening a task retains those records and evaluates any currently applicable reminder not previously delivered for that task, recipient, reminder type, and deadline.
- If an update would overwrite a change made since the employee loaded the task, it is rejected with a conflict message and an opportunity to reload; existing changes are not silently lost.
- If the assignee becomes inactive, the creator retains access and can reassign the task; an inactive employee cannot sign in or receive new assignments or reminders.
- An attachment upload interrupted by network loss must not produce an accessible partial file. Previously uploaded attachments remain accessible to current participants.
- Reassignment applies immediately to task content, files, search results, notification previews, and reminder recipients.
- Only one deadline-change request may be pending per task. The requester may withdraw it before a decision and submit another. A direct deadline edit, reassignment, completion, or cancellation closes any pending request as superseded and notifies its requester only while they retain task access.
- Deadline-change decisions against an already resolved or superseded request are rejected with an explicit conflict message. Approval of a past deadline requires the same confirmation as a direct deadline edit.
- Leaving Blocked clears the current blocked reason while preserving its historical value. A self-assigned task uses direct deadline editing rather than requesting approval from the same person.
- Email delivery rechecks current recipient access immediately before sending. Disabling email suppresses pending delivery; re-enabling applies to new events only. Delivery failure must not undo task actions or their in-app notifications.
- Cancelling a Done task is rejected; it must first be reopened. Cancelled tasks retain their content, allow further comments/files and creator field edits, but cannot accept deadline-change requests until reopened.
- Undated tasks show No deadline and are excluded by due-date-range filters; a separate No deadline filter finds them. Removing a deadline cancels pending deadline reminders and supersedes a pending deadline-change request. An assignee may request a specific date for an undated open task but cannot request removal of a deadline.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A user authenticated by the separate user-management system MUST be able to access task management without additional enrollment or a task-specific account. User provisioning, sign-in, and account lifecycle management MUST remain outside task management.
- **FR-002**: The system MUST allow an employee to create a task with a required nonempty title and one assignee, an optional description and completion date, and a priority of Low, Normal, or High, defaulting to Normal. The creator and creation time MUST be recorded.
- **FR-003**: The system MUST support To do, In progress, Blocked, Done, and Cancelled statuses and default new tasks to To do. Either current participant MUST be able to move an open task between To do, In progress, Blocked, and Done. Entering Blocked MUST require a nonempty reason visible with the status; leaving Blocked MUST clear the current reason while retaining history. Entering Done MUST record completion time; either participant reopening Done MUST set To do and clear the current completion time while preserving history. Open means To do, In progress, or Blocked; only open tasks are eligible for overdue indicators, deadline reminders, and deadline-change requests. Cancellation and reopening Cancelled MUST follow FR-024.
- **FR-004**: Only the creator and current assignee MUST be able to discover or access a task, its comments, attachments, activity, and task-related notification content. Membership in the company alone MUST NOT grant task access.
- **FR-005**: Only the creator MUST be able to edit title, description, priority, deadline, or assignee. Reassignment MUST preserve existing content and history, grant the new assignee access, and revoke the former assignee's access unless they are also the creator.
- **FR-006**: Both current participants MUST be able to add nonempty plain-text comments. Comments MUST retain their author, posting time, and chronological position.
- **FR-007**: Both current participants MUST be able to attach and download files independently of comments. Each attachment MUST retain its original filename, size, uploader, and upload time.
- **FR-008**: File uploads MUST be limited to PDF, PNG, JPEG, TXT, DOCX, and XLSX files of at most 10 MB each, where 1 MB equals 1,000,000 bytes. File contents MUST match a permitted format. Unsupported, mismatched, oversized, or incomplete uploads MUST be rejected with a specific error and MUST NOT be offered for download.
- **FR-009**: The system MUST provide Assigned to me and Created by me lists with title, creator, assignee, priority, status, and deadline or No deadline. It MUST support case-insensitive title/description substring search; combinable status, priority, assignee, and inclusive due-date-range filters; a No deadline filter mutually exclusive with a due-date range; and ascending/descending deadline sorting with creation order as a stable tie break and undated tasks last in either direction. Due-date-range filters MUST exclude undated tasks.
- **FR-010**: When a deadline is supplied, the system MUST store an unambiguous deadline and display its time zone. Date-only input MUST resolve to 23:59 in the creator's selected time zone. A task MUST be overdue exactly when it has a deadline that has passed and its status is To do, In progress, or Blocked. Undated tasks MUST NOT be overdue.
- **FR-011**: The system MUST require confirmation before accepting a past deadline for an open task and MUST explain the resulting overdue status. Reopening a dated task whose deadline has passed MUST require the same confirmation.
- **FR-012**: The system MUST provide a private in-app notification inbox with read/unread state and task links. Assignment and reassignment MUST notify the current assignee; comments, attachment additions, deadline changes, and status changes MUST notify the other current participant. All action-generated notifications MUST exclude the actor, including assignment to oneself. Scheduled reminders MUST deduplicate recipients when creator and assignee are the same person. Only a notification's recipient MUST be able to access it, subject to current task access.
- **FR-013**: An open task with a deadline MUST generate one due-soon reminder to its current assignee 24 hours before its deadline, or immediately if created or rescheduled within that future window. It MUST generate one overdue notification to each current participant when its deadline passes, or immediately after a confirmed past deadline is accepted. Undated, Done, and Cancelled tasks MUST NOT generate deadline reminders. Under normal operation, reminders MUST appear within five minutes of their trigger.
- **FR-014**: Completion or cancellation MUST suppress outstanding deadline reminders, and a deadline change MUST cancel pending reminders for the old deadline and reevaluate scheduling for the new one without deleting delivery records. Repeated processing, reassignment, reopening, or restoring a previously used deadline MUST NOT deliver the same reminder type twice to the same person for the same task and deadline. Reopening MUST evaluate reminders against the retained deadline and deliver any currently applicable reminder not previously delivered. Current access MUST be rechecked before delivery and notification display.
- **FR-015**: The system MUST retain a participant-readable, non-editable chronological activity history for creation, reassignment, task-field changes, status changes, comments, and attachment additions. Each event MUST identify its actor and time; field and status changes MUST show previous and new values.
- **FR-016**: Task changes, comments, attachments, history, and notification read states MUST persist across sign-out and subsequent sign-in. Failed operations MUST show actionable errors and MUST NOT appear successful or create duplicate entries when retried.
- **FR-017**: The system MUST detect conflicting edits and reject stale updates with a clear conflict message rather than silently overwrite another participant's changes.
- **FR-018**: An inactive assignee MUST retain their historical attribution but MUST lose access and eligibility for new assignments and reminders. The creator MUST remain able to reassign the task.
- **FR-019**: A signed-in user MUST be able to search eligible active users by display name or email when assigning or reassigning a task. Results MUST show display name and email to distinguish users, MUST exclude inactive users, and MUST NOT reveal task content. Empty searches MUST prompt for search text; no matches and search failures MUST have distinct messages.
- **FR-020**: The current assignee of an open task MUST be able to submit one pending deadline-change request containing a specific proposed deadline different from the current deadline, if any, and a nonempty reason, or withdraw their pending request. Only the creator MUST be able to approve or decline it. Approval MUST apply the proposed deadline using the normal confirmation and reminder rules; a pending or declined request MUST NOT change the effective deadline. Self-assigned tasks MUST use direct deadline editing instead.
- **FR-021**: Deadline-change requests MUST have Pending, Approved, Declined, Withdrawn, or Superseded states. Direct deadline editing, reassignment, completion, or cancellation MUST supersede a pending request. Stale decisions MUST fail explicitly without changing the task. Submission MUST notify the creator; decisions MUST notify the current assignee; withdrawal MUST notify the creator. Automatic supersession MUST notify the requester only if they retain access. Every request transition and blocked-reason change MUST appear in task history with actor, time, and relevant values.
- **FR-022**: The system MUST support optional email delivery for assignment/reassignment, deadline-change request submission and approval/decline, due-soon reminders, and overdue alerts, to the same eligible recipients as their in-app notifications. Comments, file additions, ordinary field/status changes, withdrawals, and supersession MUST remain in-app only. Emails MUST contain only a generic event prompt and a sign-in-protected task link, not private task details.
- **FR-023**: Email delivery MUST be independently enableable/disableable and its sending method replaceable without changing task behavior or in-app notifications. Disabled delivery MUST NOT attempt sends or accumulate a backlog; disabling MUST suppress pending sends, and re-enabling MUST apply only to new events. Sending MUST recheck recipient access, prevent duplicate email for the same event and recipient, and expose failed delivery for operator investigation without undoing successful task actions.
- **FR-024**: Only the creator MUST be able to cancel an open task with a nonempty reason or reopen a Cancelled task to To do. Cancellation MUST preserve all content, record the reason, actor, and time in history, suppress deadline reminders, supersede pending deadline-change requests, and notify the other participant in-app. Cancelled tasks MUST remain searchable and MUST NOT be labeled overdue or completed. Reopening MUST retain the deadline, clear the current cancellation reason while retaining history, notify the other participant in-app, and resume reminder evaluation under FR-014.
- **FR-025**: Only the creator MUST be able to add or remove a task deadline directly. Adding one MUST apply the standard confirmation and reminder rules. Removing one MUST suppress pending deadline reminders, remove overdue status, supersede any pending deadline-change request, and record and notify the change as a deadline edit. An assignee MUST be able to propose a specific date for an undated open task under FR-020, but requests to remove a deadline are outside scope.

### Key Entities *(include if feature involves data)*

- **Employee**: User supplied by separate user management, with a stable identity, display name, email, active/inactive state, and selected time zone. Task ownership and authorship reference the stable identity rather than a changeable name or email.
- **Task**: Delegated work with title, description, creator, current assignee, priority, status, current blocked or cancellation reason when applicable, optional deadline, creation time, and current completion time.
- **Deadline-Change Request**: Task-linked proposal with requester, proposed deadline, reason, state, submission time, decision actor/time where applicable, and at most one pending request per task.
- **Comment**: Task discussion entry with author, text, and posting time.
- **Attachment**: Task-associated file with original filename, permitted format, size, uploader, upload time, and private downloadable content.
- **Activity Event**: Immutable task-history entry identifying an action, actor, time, and changed values where relevant.
- **Notification**: Private employee-directed alert with event type, task reference, delivery time, and read/unread state, subject to current task access.
- **Reminder Record**: Delivery record associated with a task, deadline, recipient, and reminder type to prevent duplicate reminders.
- **Email Delivery**: Optional delivery of an eligible notification, with event, recipient, pending/sent/failed/suppressed state, and failure information for operators. Task content remains subject to current access rather than copied into email.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: At least 90% of 10 or more representative employees can create and assign a task with a deadline in under two minutes without assistance.
- **SC-002**: At least 90% of the same participants can locate an assigned task, comment, attach a supported file, and mark it Done within three minutes without assistance.
- **SC-003**: All access-control acceptance checks deny uninvolved and former-assignee accounts task details, search results, comments, files, history, and notification content.
- **SC-004**: Across 100 scheduled reminder cases covering rescheduling, restoring a previously used deadline, completion, reopening, reassignment, self-assignment, and repeated delivery attempts, all eligible reminders arrive within five minutes of their trigger with zero duplicate or unauthorized deliveries.
- **SC-005**: With 1,000 accessible tasks per employee and 50 simultaneous active employees, at least 95% of list, search, filter, and task-detail actions display results within two seconds.
- **SC-006**: In all persistence and activity-history acceptance cases, successful changes remain visible after sign-in again and every specified action has an accurate actor and time with no missing or duplicate history entries.
- **SC-007**: All blocked-task and deadline-request acceptance cases preserve the effective deadline until approval, require a blocked reason, prevent stale decisions, and retain an accurate history of reasons and request transitions.
- **SC-008**: All enabled/disabled/failing-email acceptance cases preserve task actions and in-app notifications, send no duplicate or unauthorized email, disclose no private task details, and send no disabled-period backlog after re-enabling. With email enabled and its delivery service available, eligible emails MUST be submitted for delivery within five minutes of their event.
- **SC-009**: All cancellation acceptance cases enforce creator-only cancellation/reopening, require a reason, retain content/history, suppress reminders while Cancelled, and never represent cancellation as completion.
- **SC-010**: All undated-task acceptance cases allow creation and assignment without a date, show No deadline, produce zero deadline reminders and overdue labels, and correctly handle filtering, sorting, adding dates, and removing dates.

## Assumptions

- Confirmed with the user: the first release serves one internal team or company, follows simple delegation rather than project coordination or completion approvals, and keeps tasks private to their creator and current assignee.
- Confirmed during clarification: Blocked requires a reason, and assignees can request deadline changes for creator approval; task completion itself does not require approval.
- Confirmed during clarification: email is an optional channel for selected events, independent of in-app notifications; its sending method may change and sending may be disabled.
- Confirmed during clarification: task deadlines are optional, including at creation and assignment.
- Proposed defaults, not separately confirmed: a browser-based experience, the stated priorities, attachment limits, and reminder schedule. Email enablement at launch and the sending service will be selected during planning.
- User management and authentication are separate from task management. A logged-in user needs no additional task-specific enrollment; Supabase is the user-selected service constraint, with integration details deferred to planning.
- User search uses the eligible user directory supplied by separate user management; task management does not introduce company-domain registration or administrator approval. Search visibility does not grant task access.
- Devices have network access; offline editing is outside the first release.
- Projects, subtasks, shared boards, completion-approval workflows, recurring tasks, dependencies, time tracking, reporting dashboards, push notifications, external collaborators, and multiple company workspaces are outside the first release. Deadline-change approval and optional email delivery for selected events are included.
- Creator-only cancellation with a reason and reopening are included. Permanent task deletion, comment editing/deletion, and attachment removal are outside the first release; Done and Cancelled tasks remain searchable and can be reopened under their respective permissions.
- Future ideas to revisit after validating simple delegation include projects and subtasks for larger work, boards for team coordination, and creator approval for formal handoffs. These are not first-release requirements.
- The project constitution currently contains template placeholders rather than adopted principles; no additional governance requirements are inferred from it.
