# Feature Specification: Private Task Delegation

**Feature Branch**: No feature branch created by this specification workflow.

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "build a task management tool, where user can create a task for another user, add comments, attachments, set to be completed by. explore other ideas with me"

## Clarifications

### Session 2026-10-27

- Q: How should employees get access to the tool? A: A separate user-management system provides sign-in and user management; a logged-in user needs no additional task-management enrollment and can search other users to assign work.
- Q: What should happen when an assignee cannot meet the deadline or is waiting on someone else? A: Add Blocked with a required reason and allow the assignee to request a deadline change that the creator approves or declines.
- Q: How should employees receive task updates and reminders when they are not using the tool? A: All updates remain in-app; prepare optional email for assignments, deadline-change requests and decisions, and deadline reminders, with delivery decoupled so the sending method can change or sending can be disabled.
- Q: What should happen when a task is no longer needed? A: Only the creator can cancel it with a reason or reopen it later; retain content and history and stop reminders while cancelled.
- Q: Should every task require a deadline, or should employees also be able to assign work without one? A: Deadlines are optional; undated tasks remain in personal lists and have no deadline reminders or overdue status until a date is set.
- Q: Should the browser access task data directly in shared storage, or must task operations go through task management's own rules? A: Task management alone governs all task reads/writes, comments, attachment authorization, and history; notification delivery is handled separately. User management supplies sign-in, and the browser may sign in with it directly, but no task content may be reached without task management's authorization.
- Q: Where must the first release run, and who should be able to reach it? A: Local development/demo only; the whole system, including user management and storage, must start locally with one documented command. Production hosting is outside this release.
- Q: How quickly should another user's changes appear on an already-open task or task list? A: Refresh visible task data and notification counts within 15 seconds while active, and initiate a refresh immediately on returning to the tab; no live subscriptions are required.
- Q: Should a former assignee lose access to every attachment link as soon as the task is reassigned away from them? A: Yes; check current task access for every new download, including old links. Current participants can access all historical task content, including content predating their assignment; previously downloaded files cannot be revoked.
- Q: What should happen to task changes and reminders if notification delivery stops and later resumes? A: Keep task operations working; save task changes, history, and a durable notification handoff together. After recovery, catch up within five minutes, recheck current access and deadlines, suppress obsolete reminders, and avoid duplicates.

- Q: Should the first version let users customize their task table by hiding and reordering columns? A: Yes; remember column visibility/order per user in this browser, not across devices.
- Q: Should task-list search, filters, and sorting survive refresh and browser Back/Forward through the page URL? A: Store non-text filters and sorting in the URL; keep free-text search only for the current page view and exclude paging positions and private task text.

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
5. **Given** a newly assigned participant, **When** they open the task, **Then** they can read all earlier comments, description changes, metadata, history, and attachments, regardless of who contributed them.
6. **Given** a former assignee who retained an attachment link, **When** they start a new download after reassignment, **Then** current authorization denies the download without exposing file content.

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
6. **Given** a connected, active task page or list, **When** the other participant changes an accessible task, **Then** its visible saved data and notification count refresh within 15 seconds without discarding unsaved edits; returning to the tab initiates an immediate refresh.
7. **Given** a signed-in employee's task list, **When** they hide and reorder columns with keyboard-operable controls and reload, **Then** that browser restores their layout for that list while keeping Title visible; reset restores defaults and another account does not inherit the layout.
8. **Given** selected non-text filters and sorting, **When** the employee reloads or uses Back/Forward, **Then** the URL restores those settings, free-text search does not appear in the URL, and copying the URL to another account reveals only that account's authorized results.
9. **Given** invalid or contradictory list URL parameters, **When** the page opens, **Then** it shows an actionable validation error rather than executing an unintended query.

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
9. **Given** notification delivery is unavailable, **When** a participant saves a task change, **Then** the task and history persist with durable pending notification work; after delivery resumes, still-applicable notifications appear within five minutes without duplicates or unauthorized content.

### Edge Cases

- Missing or whitespace-only titles, invalid supplied dates, and inactive assignees are rejected with field-specific explanations. An absent deadline is valid.
- Task-column preferences store only column identifiers/order/visibility, scoped to the signed-in user and list type in this browser; never save task content or sign-in credentials with them. Invalid saved preferences reset with an explanatory notice. If the browser cannot save preferences, customization works for the session and a notice explains that it cannot be saved.
- A creator may assign work to themselves; personal lists may both contain that task. Actions do not notify their own actor, and scheduled reminders go to that person only once per reminder type and deadline.
- Creating or rescheduling an open task with a past deadline requires explicit confirmation; the task is immediately overdue and receives one overdue notification per current participant.
- A date without a time means 23:59 on that date in the creator's selected time zone. Daylight-saving changes must not cause reminders or overdue indicators to disagree with the displayed deadline.
- A task created or rescheduled less than 24 hours before its future deadline receives one immediate due-soon reminder instead of missing the reminder window.
- Completion or cancellation suppresses pending deadline reminders. A changed deadline cancels pending reminders for the old deadline and reevaluates scheduling for the new deadline without deleting delivery records. Restoring a previously used deadline or reopening a task retains those records and evaluates any currently applicable reminder not previously delivered for that task, recipient, reminder type, and deadline.
- If an update would overwrite a change made since the employee loaded the task, it is rejected with a conflict message and an opportunity to reload; existing changes are not silently lost.
- If the assignee becomes inactive, the creator retains access and can reassign the task; an inactive employee cannot sign in or receive new assignments or reminders.
- An attachment upload interrupted by network loss must not produce an accessible partial file. Previously uploaded attachments remain accessible to current participants.
- Reassignment applies immediately to server authorization for task content, files, search results, notification previews, and reminder recipients. Previously displayed client content is cleared on refresh or a denied action under FR-026; downloaded files cannot be erased remotely.
- Only one deadline-change request may be pending per task. The requester may withdraw it before a decision and submit another. A direct deadline edit, reassignment, completion, or cancellation closes any pending request as superseded and notifies its requester only while they retain task access.
- Deadline-change decisions against an already resolved or superseded request are rejected with an explicit conflict message. Approval of a past deadline requires the same confirmation as a direct deadline edit.
- Leaving Blocked clears the current blocked reason while preserving its historical value. A self-assigned task uses direct deadline editing rather than requesting approval from the same person.
- Email delivery rechecks current recipient access immediately before sending. Disabling email suppresses pending delivery; re-enabling applies to new events only. Delivery failure must not undo task actions or their in-app notifications.
- Cancelling a Done task is rejected; it must first be reopened. Cancelled tasks retain their content, allow further comments/files and creator field edits, but cannot accept deadline-change requests until reopened.
- Undated tasks show No deadline and are excluded by due-date-range filters; a separate No deadline filter finds them. Removing a deadline cancels pending deadline reminders and supersedes a pending deadline-change request. An assignee may request a specific date for an undated open task but cannot request removal of a deadline.
- Background refresh MUST NOT overwrite unsaved form or comment text. Revoked access MUST clear previously displayed task content on the next access-denied response; every subsequent operation checks current access immediately. If refresh fails, show that displayed data may be stale rather than implying it is current.
- Attachment downloads authorized before reassignment may finish; every new download, including a retry or a resumed partial download, MUST recheck current access. No cached copy may serve attachment content to a former participant without this check.
- After a notification-delivery outage, a task now past its deadline receives only an applicable overdue reminder, not a stale due-soon reminder. Done, Cancelled, undated, rescheduled, or inaccessible tasks MUST NOT cause obsolete reminder deliveries. Optional email remains subject to enablement and suppression rules during catch-up.

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
- **FR-026**: While connected and active, visible task details, task lists, and notification counts MUST reflect saved changes within 15 seconds. Returning to the tab MUST initiate an immediate refresh. Refreshes MUST preserve unsaved input, display stale-data errors on failure, and clear inaccessible task content when access is denied. Access decisions MUST NOT depend on permissions remembered by the employee's browser.
- **FR-027**: Current participants MUST have access to all retained historical task content, including previous descriptions, metadata, comments, history, and attachments predating their assignment, regardless of contributor. This MUST NOT grant access to another person's private notification inbox. Every attachment download MUST pass current task authorization at the time of the request; no reusable or shared link may bypass revocation. Previously downloaded files and requests authorized before revocation are outside the revocation guarantee.
- **FR-028**: Successful task changes MUST durably retain their associated history and notification handoff as one all-or-nothing operation. A notification-delivery outage MUST NOT prevent task operations when their required storage is available. Within five minutes after delivery recovers under the demo load defined in Assumptions, still-applicable pending notifications and missed deadline reminders MUST be processed with current access and task-state checks, obsolete reminders suppressed, and no duplicate deliveries. Pending work and processing errors MUST be visible to operators. If the task/history/handoff cannot be saved together, the action MUST fail explicitly rather than report success.

- **FR-029**: Users MUST be able to hide/show and reorder task-list columns using keyboard-operable controls and reset them to defaults. Title MUST remain visible so every row retains a task link. Preferences MUST persist per signed-in user and list type in the current browser only, without saving task content or granting access. Account switching MUST NOT apply another account's preferences. Server-side filters/sorting MUST remain independent of column visibility.
- **FR-030**: Task-list view, status, priority, assignee, due-date/no-deadline filters, and sorting MUST be encoded as validated URL parameters and restored on refresh and Back/Forward navigation. Free-text search MUST be kept only for the current page view and reset on reload; paging positions, sign-in credentials, and private task text MUST NOT enter the page URL. Invalid or contradictory URL parameters MUST show an actionable validation error rather than run an unintended query. URL values MUST NOT grant access; sign-out/account switch MUST clear search, paging positions, and account-specific list parameters before loading the next user's list. Changing query conditions MUST reset pagination.
- **FR-031**: Every user-facing workflow, including task creation/assignment/editing, status, blocked, cancellation and deadline-request actions, comments and attachments, list search/filter/sort/column customization, and the notification inbox, MUST conform to WCAG 2.1 Level AA and the applicable EN 301 549 requirements under the European Accessibility Act. Every workflow MUST be operable by keyboard alone with visible, logical focus. Controls, form fields, validation and conflict errors, status changes, overdue and unread indicators, and refreshed content MUST expose their names, roles, states, and relationships to assistive technologies. Status, priority, overdue, and unread information MUST NOT rely on color alone. Background refreshes MUST NOT move focus or repeatedly announce unchanged content; outcomes of a user's own action and new notifications MUST be announced without interrupting input. Content MUST remain usable at 200% zoom and reflow at a 320-pixel-wide viewport without loss of information or function, and meaningful non-text content MUST have text alternatives.

### Key Entities *(include if feature involves data)*

- **Employee**: User supplied by separate user management, with a stable identity, display name, email, active/inactive state, and selected time zone. Display name and time zone are personal profile preferences that the employee may change; identity, email, and active state are controlled only by user management. Task ownership and authorship reference the stable identity rather than a changeable name or email.
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
- **SC-008**: All enabled/disabled/failing-email acceptance cases preserve task actions and in-app notifications, send no duplicate or unauthorized email, disclose no private task details, and send no disabled-period backlog after re-enabling. With email enabled and the email provider available, eligible emails MUST be submitted for delivery within five minutes of their event.
- **SC-009**: All cancellation acceptance cases enforce creator-only cancellation/reopening, require a reason, retain content/history, suppress reminders while Cancelled, and never represent cancellation as completion.
- **SC-010**: All undated-task acceptance cases allow creation and assignment without a date, show No deadline, produce zero deadline reminders and overdue labels, and correctly handle filtering, sorting, adding dates, and removing dates.
- **SC-011**: On a documented supported development machine with required configuration, the full local demo starts with the single documented start command, reports that all parts are healthy, and supports a two-user assignment/comment/file/completion flow. An ordinary stop and restart of the whole system retains accounts, tasks, history, and files.
- **SC-012**: In all two-user freshness acceptance cases under normal connectivity, saved changes appear on the other active screen within 15 seconds, returning to a tab triggers refresh, and unsaved input is not lost.
- **SC-013**: All reassignment/download acceptance cases grant new participants access to retained historical content and deny every new file request by former participants, including requests using previously copied links or previously used download addresses.
- **SC-014**: In stop/restart acceptance cases under the demo load defined in Assumptions, successful task mutations retain history and pending notification work across restarts of any part of the system; within five minutes of notification-delivery recovery, all still-applicable notifications are processed with zero lost, duplicate, or unauthorized deliveries and zero obsolete reminders.

- **SC-015**: All column-customization acceptance cases preserve visibility/order after page reload in the same browser/account, isolate different accounts and list types, keep a visible task link, support keyboard customization/reset, and save no task content.
- **SC-016**: All list-navigation acceptance cases restore valid non-text filters and sorting after refresh/Back/Forward, exclude free-text search, paging positions, and sign-in credentials from the page URL, reject invalid parameters visibly, and enforce current access when a URL is copied to another account.
- **SC-017**: Before release, every core workflow (create/assign, task update and status, comment/attachment, list navigation and customization, notifications) has zero WCAG 2.1 Level AA violations in automated accessibility checks, and keyboard-only and screen-reader reviews complete each workflow end to end with zero unresolved accessibility defects.

## Assumptions

- Confirmed with the user: the first release serves one internal team or company, follows simple delegation rather than project coordination or completion approvals, and keeps tasks private to their creator and current assignee.
- Confirmed during clarification: Blocked requires a reason, and assignees can request deadline changes for creator approval; task completion itself does not require approval.
- Confirmed during clarification: email is an optional channel for selected events, independent of in-app notifications; its sending method may change and sending may be disabled.
- Confirmed during clarification: task deadlines are optional, including at creation and assignment.
- Confirmed during clarification: personal column visibility/order is included and stored per user/list in this browser only. Customizing columns never changes which tasks the server returns.
- Proposed defaults, not separately confirmed: a browser-based experience, the stated priorities, attachment limits, and reminder schedule. Collaborative planning confirmed email disabled by default, optional local capture of demo email, and that real sending requires a duplicate-safe delivery method; see plan.md and research.md.
- User management and authentication are separate from task management. A logged-in user needs no additional task-specific enrollment. Technology choices selected by the user (for user management, storage, live updates, the user interface, and services) are binding planning constraints recorded in plan.md and research.md, not product requirements.
- Confirmed boundary: task management alone governs task reads/writes, comments, attachment authorization, and history; notification delivery is separate. The browser may sign in directly with user management but MUST NOT reach task content without task management's authorization. Planning MUST keep task changes and their history all-or-nothing and the handoff to notification delivery reliable across this boundary.
- Confirmed deployment scope: local development/demo only; the complete system, including user management and storage, starts locally with one documented command. Production deployment, public exposure, high availability, and production recovery commitments are deferred. Persistent local data and health checks remain required; administrative access MUST remain local-only.
- Confirmed freshness contract: active views refresh within 15 seconds and on tab return. Planning additionally selected instant inbox-change hints; they carry no task identifiers or content, grant no access, and never replace periodic refresh or durable notifications. A hint channel may keep delivering generic hints briefly after access changes, so every data request still rechecks access. Authorization revocation applies immediately to data operations; clearing previously displayed content follows the next refresh or denied action, not an instantaneous remote erasure guarantee.
- User search uses the eligible user directory supplied by separate user management; task management does not introduce company-domain registration or administrator approval. Search visibility does not grant task access.
- Devices have network access; offline editing is outside the first release.
- Demo load means up to 50 simultaneously active employees, each with up to 1,000 accessible tasks (SC-005). Recovery and reminder timing targets (FR-013, FR-028, SC-004, SC-014) apply at or below this load.
- Refinement 2026-10-28: The task application may offer a profile settings screen where the signed-in employee changes their own display name and time zone. Changes are saved through user management's own profile capability; task management stores no separate profile copy and gains no ability to create, activate, deactivate, or delete accounts, so FR-001's boundary still holds. A time-zone change affects how the employee's later date-only inputs and date filters are interpreted; it does not move existing deadlines.
- Refinement 2026-10-28: FR-031 and SC-017 add accessibility obligations required by constitution v1.2.0. Accessibility is an acceptance constraint for every user story, not a separate release phase. A formal EAA applicability assessment for this internal demo is not part of this release; conformance targets apply regardless.
- Refinement 2026-10-28 (technology neutrality): technology names were moved out of this specification into research.md, which keeps the original clarification and assumption wording verbatim. No requirement, decision, or scope changed.
- Projects, subtasks, shared boards, completion-approval workflows, recurring tasks, dependencies, time tracking, reporting dashboards, native/device push notifications, external collaborators, and multiple company workspaces are outside the first release. Deadline-change approval, private in-browser inbox-change hints, and optional email delivery for selected events are included.
- Creator-only cancellation with a reason and reopening are included. Permanent task deletion, comment editing/deletion, and attachment removal are outside the first release; Done and Cancelled tasks remain searchable and can be reopened under their respective permissions.
- Future ideas to revisit after validating simple delegation include projects and subtasks for larger work, boards for team coordination, and creator approval for formal handoffs. These are not first-release requirements.
- Constitution v1.2.1 governs architecture boundaries, the reproducible local environment, security and vulnerability checks, automated testing, and accessible user interfaces (EAA, EN 301 549, WCAG 2.1 AA); plan.md records how this feature complies.
