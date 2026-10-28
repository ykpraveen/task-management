---
description: "Implementation tasks for Private Task Delegation"
---

# Tasks: Private Task Delegation

**Input**: Design documents in `specs\001-task-delegation\`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts\`, `quickstart.md`

**Organization**: Tasks are grouped by the four prioritized user stories. Java behavior, service contracts, and acceptance journeys include automated test work as required by the plan and project constitution.

## Phase 1: Setup

**Purpose**: Establish the planned repository, build, and container structure.

- [ ] T001 Create the planned repository directories `apps\web\`, `services\gateway\`, `services\task-api\`, `services\notifications\`, `packages\api-client\`, `infra\supabase\`, `infra\identity\`, `infra\containers\`, `tests\contract\`, `tests\e2e\`, `tests\performance\`, and `scripts\`
- [ ] T002 [P] Create the Java 25 Maven reactor and Maven Wrapper in `pom.xml`, `.mvn\wrapper\`, and `mvnw.cmd`; configure the Maven compiler release and required toolchain as 25, pin Spring Boot 4.1.2, and use the compatible Spring Cloud 2025.1.x BOM
- [ ] T003 [P] Create the npm workspace manifests and lockfile in `package.json` and `package-lock.json` for `apps\web\` and `packages\api-client\`
- [ ] T004 [P] Add root `compose.yaml` service profiles, persistent volumes, local-only port bindings, and health-check dependency structure for the planned application and Supabase services
- [ ] T005 [P] Add placeholder-only `.env.example` and ignore generated secrets, runtime keys, local environment files, and build outputs in `.gitignore`
- [ ] T006 Add the idempotent local secret/key generation and demo bootstrap entrypoints in `scripts\setup.ps1` and `scripts\demo-users.ps1` without printing secrets or overwriting existing credentials
- [ ] T007 [P] Add pinned upstream Supabase Compose configuration and required Auth, PostgreSQL, Storage, gateway, and Realtime settings in `infra\supabase\compose.yaml` and `infra\supabase\kong.yml`
- [ ] T008 [P] Add reproducible Java 25 builder/runtime and frontend container build definitions in `infra\containers\service.Dockerfile` and `infra\containers\web.Dockerfile`

---

## Phase 2: Foundational

**Purpose**: Implement shared identity, contract, persistence, security, and runtime foundations that block all user stories.

- [ ] T009 [P] Create identity-owned directory projection migrations and the fixed-subject active-user helper in `infra\identity\migrations\V001__identity_directory.sql`
- [ ] T010 Create Flyway configuration and separate identity, task, and notification schemas, non-owner runtime roles, least-privilege grants, and per-table bigint identity-sequence ownership in `services\task-api\src\main\resources\db\migration\V001__task_schema.sql` and `services\notifications\src\main\resources\db\migration\V001__notification_schema.sql`
- [ ] T011 [P] Implement identity lifecycle synchronization and the read-only directory contract in `infra\identity\src\main\java\com\example\identity\DirectoryProjection.java`
- [ ] T012 [P] Configure Spring Security JWT resource-server validation for exact issuer, audience, ES256 algorithm, expiry, JWKS refresh, active-user checks, and UUID identity mapping in `services\task-api\src\main\java\com\example\tasks\security\JwtSecurityConfiguration.java` and `services\notifications\src\main\java\com\example\notifications\security\JwtSecurityConfiguration.java`
- [ ] T013 Implement distinct internal service credentials, explicit worker scopes, bounded timeouts, and rejection of public/internal-header spoofing in `services\task-api\src\main\java\com\example\tasks\security\InternalServiceSecurity.java` and `services\notifications\src\main\java\com\example\notifications\security\InternalServiceSecurity.java`
- [ ] T014 [P] Define shared problem-details responses, decimal-string bigint ID schemas, UUID identity schemas, ETags, idempotency headers, and cursor conventions in `packages\api-client\openapi\common.yaml`
- [ ] T015 Implement gateway allowlisted Supabase/Auth/API/Realtime routes, WebSocket upgrade handling, authenticated request rate limits, origin checks, and query-string redaction in `services\gateway\src\main\resources\application.yml`
- [ ] T016 [P] Add task and notification OpenAPI contract sources matching `contracts\http-api.md` and `contracts\delivery.md` in `services\task-api\openapi\http-api.yaml` and `services\notifications\openapi\http-api.yaml`
- [ ] T017 Generate the TypeScript API client and canonical positive decimal-string ID validators in `packages\api-client\src\generated\` and `packages\api-client\src\ids.ts`, rejecting JSON-number, noncanonical, zero, negative, and overflow IDs
- [ ] T018 Create the React/Vite application shell and configure TanStack Router, Query, Form, and Table in `apps\web\src\main.tsx` and `apps\web\src\app.tsx`
- [ ] T019 [P] Configure shared service error mapping, structured redacted logging, liveness/readiness probes, and non-sensitive operational metrics in `services\task-api\src\main\java\com\example\tasks\operations\` and `services\notifications\src\main\java\com\example\notifications\operations\`
- [ ] T020 [P] Add Java 25 PostgreSQL-backed JUnit and Spring integration, Vitest/Testing Library, and Playwright test profiles and commands in `pom.xml`, `package.json`, and `compose.yaml`
- [ ] T087 [P] Add Gateway JUnit unit and Spring integration tests for route allowlists, authentication and rate limits, origin checks, query-string redaction, and WebSocket upgrades, and ensure the CI test command executes them in `services\gateway\src\test\java\com\example\gateway\`

**Checkpoint**: Shared build, identity, security, API conventions, and database foundations are available before story implementation.

---

## Phase 3: User Story 1 - Delegate and Complete a Task (Priority: P1) 🎯 MVP

**Goal**: Let active employees privately create, assign, update, progress, reopen, cancel, and request deadline changes for tasks.

**Independent Test**: Using creator, assignee, replacement, and uninvolved accounts, create and find an assigned task; verify field/status/deadline rules, history, reassignment revocation, deadline-request decisions, and denied access for the uninvolved account.

### Tests for User Story 1

- [ ] T021 [P] [US1] Add PostgreSQL JUnit tests for task constraints, identity-generated IDs, sequence gaps, generated-key retrieval, stale versions, and task-lock serialization in `services\task-api\src\test\java\com\example\tasks\persistence\TaskRepositoryPostgresTest.java`
- [ ] T022 [P] [US1] Add JUnit service tests for task lifecycle, permissions, deadline/time-zone validation, past-deadline confirmation, cancellation, and deadline-request transitions in `services\task-api\src\test\java\com\example\tasks\service\TaskServiceTest.java`
- [ ] T023 [P] [US1] Add Spring JUnit API integration tests for creator/assignee access, outsider 404 responses, active-user rejection, idempotency replay/conflict, ETags, and field errors in `services\task-api\src\test\java\com\example\tasks\api\TaskApiIntegrationTest.java`
- [ ] T024 [P] [US1] Add Playwright acceptance coverage for create, assign, find, edit, status, reopen, cancellation, deadline requests, and three-account privacy in `tests\e2e\delegate-complete-task.spec.ts`

### Implementation for User Story 1

- [ ] T025 [P] [US1] Create task, deadline-request, activity-event, mutation-receipt entities and database constraints for title `1..200`, description `0..20,000`, blocked/cancellation reasons `1..2,000`, and immutable creator in `services\task-api\src\main\java\com\example\tasks\domain\`
- [ ] T026 [P] [US1] Implement task and deadline-request repositories with creator/assignee indexes, stable `(created_at,id)` ordering, task-first row locking, identity-generated keys, and no sequence reseeding in `services\task-api\src\main\java\com\example\tasks\persistence\`
- [ ] T027 [US1] Implement task creation, creator-only field edits/reassignment, status transitions, blocked reasons, completion/reopen, cancellation, and version checks in `services\task-api\src\main\java\com\example\tasks\service\TaskService.java`
- [ ] T028 [US1] Implement deadline parsing, creator-zone date-only conversion to 23:59, DST ambiguity rejection, overdue computation, and past-deadline confirmation in `services\task-api\src\main\java\com\example\tasks\service\DeadlineService.java`
- [ ] T029 [US1] Implement deadline-request submission, withdrawal, creator approval/decline, pending uniqueness, supersession, and stale-decision conflicts in `services\task-api\src\main\java\com\example\tasks\service\DeadlineRequestService.java`
- [ ] T030 [US1] Persist allowlisted immutable task history and atomic mutation receipts with task changes in `services\task-api\src\main\java\com\example\tasks\service\TaskMutationTransaction.java`
- [ ] T031 [US1] Implement active-user directory search by case-insensitive name/email with trimmed 1..200-character queries and distinct empty/failure responses in `services\task-api\src\main\java\com\example\tasks\client\DirectoryClient.java`
- [ ] T032 [US1] Implement `/api/v1/users`, task create/list/detail/edit/status/reopen/cancel, and deadline-request endpoints with contract-compliant authorization and problem responses in `services\task-api\src\main\java\com\example\tasks\api\TaskController.java`
- [ ] T033 [US1] Implement list DTO serialization with decimal-string IDs, UUID user IDs, permissions, ETags, version strings, nullable deadlines, and current overdue state in `services\task-api\src\main\java\com\example\tasks\api\TaskDtoMapper.java`
- [ ] T034 [P] [US1] Implement generated-client-backed task/user query hooks with cancellation, scoped cache keys, and targeted invalidation in `apps\web\src\features\tasks\api.ts`
- [ ] T035 [US1] Implement create/edit task forms with active-user search, required title, priority, optional deadline/time zone, server field errors, dirty state, idempotent retry, and explicit past-deadline confirmation in `apps\web\src\features\tasks\TaskForm.tsx`
- [ ] T036 [US1] Implement task detail and status controls for progress, blocked reason, done/reopen, creator-only cancellation, and role-based field editing in `apps\web\src\features\tasks\TaskDetails.tsx`
- [ ] T037 [US1] Implement basic Assigned to me and Created by me list screens with task links and title, creator, assignee, priority, status, and deadline/No deadline columns in `apps\web\src\features\tasks\TaskList.tsx`
- [ ] T038 [US1] Implement deadline-request forms and creator approval/decline controls with current task versions and preserved form state on errors in `apps\web\src\features\tasks\DeadlineRequestPanel.tsx`
- [ ] T039 [US1] Add task history pagination endpoint and participant-readable chronological history UI for creation, field/status changes, reassignment, and deadline-request transitions in `services\task-api\src\main\java\com\example\tasks\api\TaskHistoryController.java` and `apps\web\src\features\tasks\TaskHistory.tsx`

**Checkpoint**: The P1 task delegation increment works without comments, files, advanced list navigation, or notification delivery.

---

## Phase 4: User Story 2 - Discuss Work and Share Files (Priority: P2)

**Goal**: Let current participants exchange append-only comments and upload/download validated private files while preserving history and access revocation.

**Independent Test**: On a task created by US1, add comments as both participants and upload/download each supported format; verify ordering, validation failures, partial-upload cleanup, outsider denial, and former-assignee denial on a copied download URL.

### Tests for User Story 2

- [ ] T040 [P] [US2] Add JUnit tests for comment validation/order, upload idempotency, format/content mismatches, size limits, bounded archive parsing, interrupted staging, and authorization recheck in `services\task-api\src\test\java\com\example\tasks\attachments\AttachmentServiceTest.java`
- [ ] T041 [P] [US2] Add PostgreSQL/Spring integration tests for atomic comment/file history and outbox finalization, current-participant reads, and former-assignee download denial in `services\task-api\src\test\java\com\example\tasks\api\TaskContentIntegrationTest.java`
- [ ] T042 [P] [US2] Add Playwright coverage for participant comments, supported/unsupported/oversized files, retry behavior, and copied attachment-link revocation in `tests\e2e\discuss-and-share-files.spec.ts`

### Implementation for User Story 2

- [ ] T043 [P] [US2] Add append-only comment and staged attachment tables, unique upload-operation identity, ready-only visibility, and indexes in `services\task-api\src\main\resources\db\migration\V002__task_comments_attachments.sql`
- [ ] T044 [US2] Implement participant-authorized comment creation and chronological `(created_at,id)` retrieval with plain-text validation in `services\task-api\src\main\java\com\example\tasks\service\CommentService.java`
- [ ] T045 [US2] Implement private object staging, streaming 10,000,000-byte enforcement, content/extension validation for PDF/PNG/JPEG/TXT/DOCX/XLSX, and bounded DOCX/XLSX archive inspection in `services\task-api\src\main\java\com\example\tasks\attachments\AttachmentValidationService.java`
- [ ] T046 [US2] Implement idempotent upload staging/finalization, task-access recheck, atomic ready metadata/history/outbox commit, and abandoned-stage cleanup in `services\task-api\src\main\java\com\example\tasks\attachments\AttachmentService.java`
- [ ] T047 [US2] Implement comment, attachment metadata, and authorized streaming/range download endpoints with no-store, nosniff, sanitized Content-Disposition, and no storage redirects in `services\task-api\src\main\java\com\example\tasks\api\TaskContentController.java`
- [ ] T048 [US2] Implement comment composer and chronological thread with retained text after failed submissions in `apps\web\src\features\tasks\CommentThread.tsx`
- [ ] T049 [US2] Implement attachment list, upload progress/retry/error states, and authenticated download controls in `apps\web\src\features\tasks\AttachmentPanel.tsx`

**Checkpoint**: Comments and private attachments work for current participants; every new download rechecks authorization.

---

## Phase 5: User Story 3 - Find and Prioritize Personal Work (Priority: P2)

**Goal**: Support performant personal task discovery with composable search/filter/sort, resilient refresh, URL-backed non-text state, and per-user table preferences.

**Independent Test**: Populate assigned and created lists with mixed statuses, priorities, assignees, dates, and undated tasks; verify combined filters/search, stable deadline sort, paging, URL Back/Forward, account isolation, keyboard column controls, and refresh within 15 seconds without losing edits.

### Tests for User Story 3

- [ ] T050 [P] [US3] Add PostgreSQL JUnit repository tests for AND-combined filters, date-zone ranges, no-deadline exclusion, stable keyset cursors, and null-last sorting in `services\task-api\src\test\java\com\example\tasks\persistence\TaskSearchRepositoryTest.java`
- [ ] T051 [P] [US3] Add frontend tests for validated filter URLs, Back/Forward restoration, cursor reset, free-text exclusion from URLs, and account-switch clearing in `apps\web\src\features\tasks\TaskListSearch.test.tsx`
- [ ] T052 [P] [US3] Add frontend tests for keyboard-operable column preferences, title visibility, storage failure/corruption notices, and user/list/browser isolation in `apps\web\src\features\tasks\TaskTablePreferences.test.tsx`
- [ ] T053 [P] [US3] Add Playwright coverage for search/filter/sort/pagination, refresh freshness, dirty-form preservation, and account switching in `tests\e2e\find-and-prioritize-work.spec.ts`

### Implementation for User Story 3

- [ ] T054 [US3] Implement bounded keyset pagination and allowlisted composable status, priority, assignee, inclusive date, no-deadline, and search filters with stable tie breaks in `services\task-api\src\main\java\com\example\tasks\persistence\TaskSearchRepository.java`
- [ ] T055 [US3] Validate list-query combinations, bind filters/sort to opaque cursors, and enforce default limit 25 and maximum 100 in `services\task-api\src\main\java\com\example\tasks\api\TaskListQuery.java`
- [ ] T056 [US3] Add case-insensitive title/description substring search indexing only after measuring query plans and document the selected PostgreSQL indexes in `services\task-api\src\main\resources\db\migration\V003__task_search_indexes.sql`
- [ ] T057 [US3] Implement Router-validated URL state for view, status, priority, assignee, date/no-deadline filters, and sort while keeping free-text search/cursors in memory in `apps\web\src\features\tasks\task-list-route.tsx`
- [ ] T058 [US3] Implement manual server-side Table filtering/sorting/pagination and accessible Load more controls in `apps\web\src\features\tasks\TaskTable.tsx`
- [ ] T059 [US3] Implement keyboard-operable column visibility/order preferences scoped to verified user and list type, keeping Title visible and reporting invalid/unavailable storage in `apps\web\src\features\tasks\TaskTablePreferences.ts`
- [ ] T060 [US3] Configure 10-second active polling, focus/reconnect refetch, bounded memory-only pages, stale-data errors, and preservation of dirty form/comment state in `apps\web\src\features\tasks\query-policy.ts`

**Checkpoint**: Personal lists remain authorized and responsive across search, navigation, refresh, and account changes.

---

## Phase 6: User Story 4 - Stay Informed Without Chasing Updates (Priority: P3)

**Goal**: Deliver private durable in-app notifications, applicable reminders, auditable history, replaceable optional email, and generic private Realtime hints.

**Independent Test**: Generate assignment, task-change, comment, attachment, deadline-request, due-soon, and overdue events; stop/restart the worker, reassign users, change deadlines, toggle email, and reconnect Realtime. Verify durable exactly-once inbox effects, correct current recipients, suppression of obsolete work, generic payloads, and recovery within five minutes.

### Tests for User Story 4

- [ ] T061 [P] [US4] Add JUnit tests for task outbox leases, fencing tokens, bounded retries, reminder identity restoration, self-assignment deduplication, and obsolete reminder suppression using a controllable clock in `services\task-api\src\test\java\com\example\tasks\delivery\OutboxReminderServiceTest.java`
- [ ] T062 [P] [US4] Add JUnit/PostgreSQL tests for notification event deduplication, payload-hash conflict, inbox uniqueness, recipient authorization, read state, and delivery-work recovery in `services\notifications\src\test\java\com\example\notifications\service\NotificationConsumerPostgresTest.java`
- [ ] T063 [P] [US4] Add JUnit tests for email policy epochs, disabled-period suppression, stable idempotency keys, ambiguous SMTP outcomes, and no automatic unknown-state replay in `services\notifications\src\test\java\com\example\notifications\delivery\EmailDeliveryServiceTest.java`
- [ ] T064 [P] [US4] Add integration tests for notification service access checks, outbox claim/ack/retry/resolve contracts, five-second timeouts, and visible dependency failures in `services\notifications\src\test\java\com\example\notifications\api\NotificationIntegrationTest.java`
- [ ] T065 [P] [US4] Add Realtime integration tests for own private topic receive-only access, wrong/anonymous/inactive topics, denied client sends/public channels, token refresh, and gateway WebSocket recovery in `tests\contract\realtime-authorization.spec.ts`
- [ ] T066 [P] [US4] Add crash/restart end-to-end coverage proving durable inbox recovery within five minutes, no duplicate notifications, stale reminder suppression, and inbox operation during Realtime outage in `tests\e2e\notification-recovery.spec.ts`

### Implementation for User Story 4

- [ ] T067 [P] [US4] Add task outbox, persistent lease, reminder identity, and operational failure migrations with stable unique event/reminder keys in `services\task-api\src\main\resources\db\migration\V004__task_outbox_reminders.sql`
- [ ] T068 [US4] Implement task-owned event envelope creation in the same transaction as task/history changes, excluding private task text and actor recipients in `services\task-api\src\main\java\com\example\tasks\delivery\TaskOutboxService.java`
- [ ] T069 [US4] Implement internal outbox claim, ack, retry, reminder evaluation, authorization check, and current-recipient resolution endpoints with bounded batches and claim-token fencing in `services\task-api\src\main\java\com\example\tasks\api\InternalDeliveryController.java`
- [ ] T070 [US4] Implement due-soon/overdue scheduling at 24 hours and deadline, catch-up against current task state, and safe reminder requeue only when no inbox delivery was committed in `services\task-api\src\main\java\com\example\tasks\delivery\ReminderScheduler.java`
- [ ] T071 [P] [US4] Add notification-owned consumed-event, inbox, delivery-work, policy-epoch, and policy-history migrations with unique source/recipient/channel constraints in `services\notifications\src\main\resources\db\migration\V001__notification_delivery.sql`
- [ ] T072 [US4] Implement transactional event consumption, payload hash validation, recipient outcome retention, inbox deduplication, and durable delivery-work creation in `services\notifications\src\main\java\com\example\notifications\service\NotificationConsumer.java`
- [ ] T073 [US4] Implement leased notification delivery workers with persisted claim tokens, bounded exponential backoff, ten-attempt failure retention, source acknowledgments, and explicit safe replay in `services\notifications\src\main\java\com\example\notifications\worker\NotificationWorker.java`
- [ ] T074 [US4] Implement notification list, unread count, read-state endpoints, and bounded current-task authorization checks that fail closed on unavailable task service in `services\notifications\src\main\java\com\example\notifications\api\NotificationController.java`
- [ ] T075 [US4] Implement email delivery policy epochs, disabled-by-default capture-only SMTP adapter, generic templates, current-access checks, and accepted/retryable/rejected/unknown outcomes in `services\notifications\src\main\java\com\example\notifications\delivery\EmailDeliveryService.java`
- [ ] T076 [US4] Configure Supabase private-only Realtime tenant, own-user receive-only `realtime.messages` policy, restricted active-user helper, and no client send/Presence policy in `infra\supabase\migrations\`
- [ ] T077 [US4] Implement server-only Supabase internal-gateway Broadcast publishing with mandatory `private:true`, generic `inbox.changed` payload containing only stable signal ID/time, and redacted credentials in `services\notifications\src\main\java\com\example\notifications\delivery\RealtimePublisher.java`
- [ ] T078 [US4] Implement frontend private `user:{auth UUID}` receive-only channel lifecycle, token refresh/teardown, subscribe-before-fetch recovery, 10-second fallback polling, and inbox query invalidation in `apps\web\src\features\notifications\realtime.ts`
- [ ] T079 [US4] Implement notification inbox, unread count, read controls, and generic email-policy operator command with recipient-scoped API state in `apps\web\src\features\notifications\NotificationInbox.tsx` and `scripts\delivery-policy.ps1`

**Checkpoint**: Task actions remain successful during notification outages, inbox work recovers durably, and Realtime/email never become authorization or task-content channels.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Validate the complete local demo, security gates, operational recovery, and measurable outcomes.

- [ ] T080 [P] Add synthetic demo-account provisioning and deterministic non-sensitive seed data for creator, assignee, replacement, and outsider in `infra\identity\src\main\java\com\example\identity\DemoUsersCommand.java`
- [ ] T081 [P] Document native Docker and Windows WSL prerequisites, setup, startup, health, demo flows, email capture, safe restart, and limitations in `README.md`
- [ ] T082 Add Compose startup ordering, migration completion checks, health dependencies, persistent storage, local-only admin bindings, and application profiles in `compose.yaml`
- [ ] T083 [P] Add performance fixtures and k6 scenarios for 1,000 tasks per user, 50 concurrent employees, and the two-second p95 list/search/detail threshold in `tests\performance\task-management.js`
- [ ] T084 [P] Add security scanner profiles and CI checks for Java/TypeScript builds, JUnit (including Gateway tests), PostgreSQL integration, automated axe-core accessibility checks for core frontend workflows, secret detection, source analysis, Maven/npm dependencies, and pinned runtime images in `.github\workflows\ci.yml`
- [ ] T085 Run the full quickstart acceptance matrix, including Compose recreation persistence, sequence IDs above 2^53-1, all user journeys, worker crashes, reminder clock cases, Realtime authorization, and disabled-email recovery; record commands/results in `specs\001-task-delegation\quickstart.md`
- [ ] T086 Verify the implementation against every FR-001..FR-030 and SC-001..SC-016, record unavailable/failed validation gates, and resolve outstanding cross-service contract gaps in `specs\001-task-delegation\checklists\requirements.md`
- [ ] T088 Complete and record manual keyboard and screen-reader review of the core create/assign, task-update, comment/attachment, list-navigation, and notification workflows; track accessibility defects and resolve them before release in `specs\001-task-delegation\checklists\accessibility.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 establishes the planned paths; independent build, manifest, Compose, Supabase, and container tasks can proceed after it.
- **Foundational (Phase 2)**: Depends on the repository/build skeleton. Identity, database ownership, JWT security, API conventions, gateway, frontend shell, and test profiles block story work.
- **User Stories (Phase 3+)**: US1 depends on the foundation. US2 and US3 depend on US1 task identity, authorization, and task APIs. US4 depends on US1 mutation/history events and the comment/file event sources from US2; it does not depend on US3.
- **Polish (Phase 7)**: Compose smoke, end-to-end, and measurable release validation depend on the relevant implemented stories; documentation and performance/security harness work can start earlier.

### User Story Dependencies

- **US1 (P1)**: Starts after Phase 2; no dependency on another story. MVP core.
- **US2 (P2)**: Depends on US1 task records and participant authorization; independently test comments/files on a seeded US1 task.
- **US3 (P2)**: Depends on US1 task records and list API; can proceed in parallel with US2 after US1.
- **US4 (P3)**: Depends on US1 task/outbox event sources and US2 comment/file event sources. It is independent of US3.

### Parallel Opportunities

- Setup: T002-T005, T007-T008 after T001 can be developed in parallel where file ownership does not overlap.
- Foundation: T009, T012, T014, T016, T019, T020, and T087 are separable; database schemas and shared contract/client scaffolding must be agreed before dependent service code.
- US1: T021-T024 tests can be authored in parallel; T025/T026 persistence and T031 directory adapter are separable before service integration; frontend API hooks and service tests can proceed alongside backend implementation.
- US2: T040-T042 tests are parallel; comment implementation and attachment validation/storage implementation are separable before controller integration.
- US3: US2 and US3 can proceed in parallel after US1; T050-T053 tests are parallel; backend query and frontend URL/table work are separable after shared query contract agreement.
- US4: T061-T066 test suites and T067/T071 service-owned migrations can proceed in parallel; task-side delivery and notification-side consumption work are independently owned behind the delivery contract.
- Polish: T080, T081, T083, T084, and T088 can proceed in parallel; run T085/T086 after implementation and required services are available. T088 is a release gate and requires the implemented core workflows.

### Parallel Examples by User Story

```text
US1 after the shared task contract is agreed:
Task: T021 PostgreSQL task repository tests in services\task-api\src\test\java\com\example\tasks\persistence\TaskRepositoryPostgresTest.java
Task: T022 task lifecycle service tests in services\task-api\src\test\java\com\example\tasks\service\TaskServiceTest.java
Task: T023 task API integration tests in services\task-api\src\test\java\com\example\tasks\api\TaskApiIntegrationTest.java
Task: T024 task delegation browser acceptance tests in tests\e2e\delegate-complete-task.spec.ts

US2 after US1 task authorization is available:
Task: T040 attachment validation/service tests in services\task-api\src\test\java\com\example\tasks\attachments\AttachmentServiceTest.java
Task: T041 task content integration tests in services\task-api\src\test\java\com\example\tasks\api\TaskContentIntegrationTest.java
Task: T042 comments and file sharing browser tests in tests\e2e\discuss-and-share-files.spec.ts

US3 after US1 list endpoints and query conventions are stable:
Task: T050 PostgreSQL search repository tests in services\task-api\src\test\java\com\example\tasks\persistence\TaskSearchRepositoryTest.java
Task: T051 Router URL-state tests in apps\web\src\features\tasks\TaskListSearch.test.tsx
Task: T052 column-preference tests in apps\web\src\features\tasks\TaskTablePreferences.test.tsx
Task: T054 backend keyset search/filter implementation in services\task-api\src\main\java\com\example\tasks\persistence\TaskSearchRepository.java
Task: T057 Router URL state in apps\web\src\features\tasks\task-list-route.tsx

US4 after the outbox/event envelope contracts are stable:
Task: T061 task outbox and reminder tests in services\task-api\src\test\java\com\example\tasks\delivery\OutboxReminderServiceTest.java
Task: T062 notification consumer PostgreSQL tests in services\notifications\src\test\java\com\example\notifications\service\NotificationConsumerPostgresTest.java
Task: T063 email policy and adapter tests in services\notifications\src\test\java\com\example\notifications\delivery\EmailDeliveryServiceTest.java
Task: T065 Realtime authorization tests in tests\contract\realtime-authorization.spec.ts
```

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1 and Phase 2, especially identity/JWT validation, database ownership, task API contract, and gateway routing.
2. Complete US1 task creation, personal lists, edits, lifecycle/deadline rules, history, and deadline requests.
3. Validate US1 independently with three employee accounts and PostgreSQL-backed JUnit/Playwright tests.
4. Stop at the P1 checkpoint before adding comments/files, advanced list preferences, or notification delivery.

### Incremental Delivery

1. Deliver setup and shared foundations.
2. Deliver US1 as the MVP.
3. Add US2 comments and private files; add US3 search, filtering, sorting, URL state, and table preferences in parallel after US1.
4. Add US4 durable inbox, reminders, email capture, and Realtime hints after event sources are available.
5. Complete cross-service Compose, crash/restart, security, performance, and acceptance validation.

### Parallel Team Strategy

After foundation, one implementer can extend US1 while another prepares US2 tests/storage and a third builds US3 search/table functionality against the agreed task API. Start US4 integration after the US1 outbox and US2 event sources are stable; service ownership permits parallel task-side and notification-side implementation.

## Notes

- Every task uses the required `- [ ] Tnnn [P?] [US?] Description with file path` checklist form; setup/foundation/polish tasks omit story labels.
- [P] marks tasks that can proceed independently without editing the same files or relying on incomplete work.
- JUnit unit and PostgreSQL/Spring integration coverage is included because it is required by the project constitution and implementation plan; frontend and end-to-end suites complement but do not replace it.
- T087 supplies the required Java test coverage for the Gateway service; T084 and T088 cover the constitution's automated and manual accessibility gates.
- IDs remain PostgreSQL `bigint` in storage/Java `Long` and canonical positive decimal strings in all JSON, URLs, cursors, and Broadcast payloads.
