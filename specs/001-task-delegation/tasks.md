---
description: "Implementation tasks for Private Task Delegation"
---

# Tasks: Private Task Delegation

**Input**: Design documents in `specs/001-task-delegation/`

**Prerequisites**: `plan.md` (revised 2026-10-28), `spec.md` (FR-001..FR-031, SC-001..SC-017),
`research.md`, `data-model.md`, `contracts/http-api.md`, `contracts/delivery.md`,
`contracts/ui-accessibility.md`, `quickstart.md`

**Tests**: Required. Constitution V mandates JUnit unit and PostgreSQL-backed Spring
integration tests for every Java module. Constitution VI and FR-031 mandate automated
accessibility checks plus manual keyboard and screen-reader review for every story. Frontend
and end-to-end suites complement JUnit tests and never replace them.

**Organization**: Tasks are grouped by the four prioritized user stories. Each story phase
ends with a checkpoint task covering an accessibility pass and a security review.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: User story the task belongs to (US1..US4)
- Paths are repository-relative and use forward slashes.

## Conventions used by every task

- **Migrations**: Version numbers are assigned when a migration merges (the next free integer
  in that service's `db/migration/` directory), not when the task is planned. Tasks name a
  migration by purpose, as `V<next>__<purpose>.sql`. Keep Flyway `outOfOrder=false` and never
  edit an applied migration.
- **IDs**: Application IDs are PostgreSQL `bigint GENERATED ALWAYS AS IDENTITY`, Java `Long`,
  and canonical positive decimal strings in JSON, URLs, cursors and Broadcast payloads. User
  IDs remain Supabase Auth UUIDs.
- **Accessibility**: Every UI task satisfies the applicable rows of
  `contracts/ui-accessibility.md`. Every UI test task runs axe-core (WCAG 2.1 A/AA tags) and
  fails on any violation.
- **Scripts**: Scripts are POSIX `sh`, run inside Compose `tools` containers, and require no
  host Java, Node or PowerShell.

---

## Phase 1: Setup

**Purpose**: Establish the repository, build, container and edge structure.

- [ ] T001 Create the planned directories `apps/web/`, `packages/api-client/`, `services/gateway/`, `services/task-api/`, `services/notifications/`, `tools/identity/`, `infra/database/bootstrap/`, `infra/identity/migrations/`, `infra/supabase/migrations/`, `infra/edge/`, `infra/containers/`, `tests/contract/`, `tests/e2e/support/`, `tests/performance/`, `scripts/` and `docs/security/`
- [ ] T002 [P] Create the Java 25 Maven reactor in `pom.xml` with modules `services/gateway`, `services/task-api`, `services/notifications` and `tools/identity`; set compiler `release` 25 and a JDK 25 toolchain; import Spring Boot 4.1.2 and the Spring Cloud 2025.1.4 BOM (2025.1.2 is the minimum supporting Boot 4.1); and commit the Maven Wrapper as `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties`
- [ ] T003 [P] Create the npm workspaces in `package.json` and `package-lock.json` for `apps/web` and `packages/api-client`, with Node.js 24 in `engines` and strict TypeScript in `tsconfig.base.json`
- [ ] T004 [P] Add a `.gitattributes` that forces LF endings for `*.sh`, `mvnw`, `*.sql`, `*.yaml` and `*.conf`, so Windows and WSL checkouts produce runnable container scripts
- [ ] T005 [P] Add a placeholder-only `.env.example` (including `DEFAULT_TIME_ZONE`). In `.gitignore`, ignore `.env`, generated runtime keys and secrets, `reports/`, `target/`, `node_modules/`, `dist/`, `coverage/`, `playwright-report/`, `.DS_Store` and `*.log`
- [ ] T006 [P] Add the root `compose.yaml` skeleton: profiles `tools`, `test`, `security` and `email-capture`; named persistent volumes; loopback-only host bindings (the edge on `127.0.0.1:8080`, Studio and capture UI on separate loopback ports); health checks; and placeholder `db-bootstrap`, `task-api-migrate` and `notifications-migrate` one-shot services
- [ ] T007 [P] Add the pinned upstream Supabase Compose baseline with multi-arch image digests and the required Auth (ES256/JWKS), PostgreSQL, Storage, Kong and Realtime (private-only tenant) settings in `infra/supabase/compose.yaml` and `infra/supabase/kong.yml`
- [ ] T008 [P] Add multi-arch (`linux/amd64`, `linux/arm64`) container builds. `infra/containers/java-service.Dockerfile` builds with `./mvnw` on JDK 25 and runs as a non-root user on a JRE 25 runtime, taking a `MODULE` build argument and an entrypoint that supports `serve` and `migrate` modes. `infra/containers/web.Dockerfile` builds the SPA into an unprivileged static server image
- [ ] T009 [P] Add the edge config in `infra/edge/default.conf`. Serve the SPA and proxy only `/api/v1/`, `/auth/v1/`, the public signing-key path and `/realtime/v1/websocket` (with upgrade headers) to the gateway. Set `Content-Security-Policy: default-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. Serve `index.html` with `Cache-Control: no-store`, and make no authentication decisions
- [ ] T010 Add `scripts/setup.sh` and a `setup` tools service in `compose.yaml`. The script idempotently generates local secrets and the Supabase ES256 signing keys into ignored runtime files; it never prints secrets and never overwrites existing credentials or volumes

---

## Phase 2: Foundational

**Purpose**: Database ownership, identity, security, contracts, gateway, frontend shell,
authentication, accessible primitives and test tooling that block every story.

- [ ] T011 [P] Create the identity directory migration in `infra/identity/migrations/V<next>__identity_directory.sql`. DirectoryUser has: `user_id` (Auth UUID, unique); `display_name` "1..200, defaults to the email local part"; `email`; `active`; `time_zone` "IANA name present in `pg_timezone_names`, default `DEFAULT_TIME_ZONE`"; `deleted` (soft-delete flag); and `updated_at`. Add `AFTER INSERT/UPDATE/DELETE` triggers on `auth.users` that upsert the row in the same transaction. Derive `active` only from server-controlled `banned_until`, `deleted_at` and confirmation, never from metadata. Take `display_name` and `time_zone` from `user_metadata`, keeping the previous zone when the new one is invalid. Add the read-only directory view for runtime roles and the restricted `identity.is_active(auth.uid())` helper for Realtime policies, which exposes no rows
- [ ] T012 Implement the privileged idempotent bootstrap in `infra/database/bootstrap/bootstrap.sql` and `infra/database/bootstrap/run.sh`. Create schemas `identity`, `task` and `notification`; owner roles `identity_owner`, `task_owner` and `notification_owner`; runtime roles `task_app` and `notification_app` (non-owner, no DDL); default privileges; and read-only directory grants for both runtime roles. `run.sh` creates these cluster-level objects idempotently with `psql`, then runs Flyway as the administrator with locations `infra/identity/migrations/` and `infra/supabase/migrations/`, history table `identity.bootstrap_schema_history` and `outOfOrder=false`. Because the job runs on every `docker compose up`, migrations added later (such as T096) are applied to existing volumes Wire it as the `db-bootstrap` job in `compose.yaml`, depending on healthy Supabase PostgreSQL. Bootstrap credentials must never reach application containers
- [ ] T013 [P] Add the `tools/identity` Maven module (`tools/identity/pom.xml`) with JUnit and PostgreSQL-backed integration tests in `tools/identity/src/test/java/com/example/identity/DirectorySyncPostgresTest.java` and `tools/identity/src/test/java/com/example/identity/DemoUsersCommandTest.java`. Cover the triggers on create, profile change, invalid time zone, ban, unban and delete; `reconcile --check` detecting deliberately introduced drift; `reconcile --repair` fixing it idempotently; summaries that exclude emails and tokens; and idempotent demo provisioning
- [ ] T014 Implement `tools/identity/src/main/java/com/example/identity/ReconcileCommand.java` (`--check` exits non-zero on drift; `--repair` upserts idempotently) and `tools/identity/src/main/java/com/example/identity/DemoUsersCommand.java`. The demo command creates synthetic creator, assignee, replacement and outsider accounts with distinct time zones through server-side Auth administration, using operator-supplied passwords and printing no secrets. Expose both as `identity-reconcile` and `demo-users` tools services in `compose.yaml`
- [ ] T015 Configure each service's Flyway `migrate` mode in `services/task-api/src/main/resources/application-migrate.yml` and `services/notifications/src/main/resources/application-migrate.yml`: owner-role credentials, a history table in the service's own schema, and `outOfOrder=false`. Wire the `task-api-migrate` and `notifications-migrate` jobs in `compose.yaml` so runtime containers start only after them and use runtime roles. Add `scripts/check-migrations.sh`, which checks each Flyway location (`services/task-api`, `services/notifications`, and the bootstrap's `infra/identity/migrations/` plus `infra/supabase/migrations/`) and fails on duplicate or gapped versions and on changes to migrations already on the main branch
- [ ] T016 [P] Configure Spring Security JWT resource-server validation in `services/task-api/src/main/java/com/example/tasks/security/JwtSecurityConfiguration.java` and `services/notifications/src/main/java/com/example/notifications/security/JwtSecurityConfiguration.java`. Check the exact issuer, the audience, ES256 only, expiry and JWKS refresh; reject API keys used as user tokens; map the UUID subject; and check the directory `active` state on every request, failing closed with 503 when it cannot be read
- [ ] T017 Implement distinct internal service credentials, explicit worker scopes, five-second bounded timeouts, and rejection of public requests carrying internal headers in `services/task-api/src/main/java/com/example/tasks/security/InternalServiceSecurity.java` and `services/notifications/src/main/java/com/example/notifications/security/InternalServiceSecurity.java`
- [ ] T018 [P] Define shared problem-details responses with `fieldErrors`, decimal-string bigint ID schemas (`1..9223372036854775807`), UUID user schemas, ETags/If-Match, `Idempotency-Key` headers and cursor conventions in `packages/api-client/openapi/common.yaml`
- [ ] T019 [P] Add task and notification OpenAPI sources matching `contracts/http-api.md` and `contracts/delivery.md` in `services/task-api/openapi/http-api.yaml` and `services/notifications/openapi/http-api.yaml`
- [ ] T020 Generate the TypeScript API client with AbortSignal support into `packages/api-client/src/generated/`, and add canonical decimal-string ID validators in `packages/api-client/src/ids.ts` that reject JSON numbers, noncanonical values, zero, negatives and overflow, with tests in `packages/api-client/src/ids.test.ts`
- [ ] T021 Implement the gateway module. Put the route allowlist in `services/gateway/src/main/resources/application.yml`: `/auth/v1/**` and the signing-key path to Supabase, `/api/v1/tasks/**` and `/api/v1/users/**` to the task API, `/api/v1/notifications/**` to notifications, and `/realtime/v1/websocket` with upgrade; no catch-all and no `/internal/**`. Put the WebFlux resource server for `/api/v1/**` (same issuer/audience/ES256/expiry rules) in `services/gateway/src/main/java/com/example/gateway/security/GatewaySecurityConfiguration.java`. Add rate limits of 120 API requests/min per identity, 30 user searches/min, and 10 new Realtime connections/min per client address, returning 429 with Retry-After. Also add origin checks, redaction of query strings and frames in logs, and stripping of client forwarding and identity headers
- [ ] T022 [P] Add gateway JUnit unit and Spring integration tests in `services/gateway/src/test/java/com/example/gateway/` for the route allowlist (including denied `/internal/**` and catch-all paths), JWT rejection cases, rate limits with Retry-After, origin checks, query-string redaction, header stripping and WebSocket upgrades
- [ ] T023 [P] Configure shared error mapping, structured redacted logging (no task text, tokens or emails), private liveness/readiness probes and non-sensitive metrics in `services/task-api/src/main/java/com/example/tasks/operations/` and `services/notifications/src/main/java/com/example/notifications/operations/`
- [ ] T024 Create the React/Vite shell with TanStack Router, Query, Form and Table providers in `apps/web/src/main.tsx` and `apps/web/src/app.tsx`. Include `lang`, a skip link, `header`/`nav`/`main` landmarks, and a route focus/title manager in `apps/web/src/a11y/RouteFocus.tsx` that moves focus to the page `h1` and updates `document.title` on navigation
- [ ] T025 [P] Implement accessible primitives in `apps/web/src/a11y/`, following the ARIA APG patterns: `Dialog.tsx` (focus containment, Escape, return focus), `Combobox.tsx` (announces result count, "no matches" and failure as distinct messages), `LiveRegion.tsx` (one polite region with an `announce()` API, rate-limited to one notification-count message per 10 seconds), `ErrorSummary.tsx` (receives focus and links to fields) and `StatusText.tsx` (text labels for status, priority, overdue and unread). Add design tokens meeting 4.5:1 text and 3:1 non-text/focus contrast in `apps/web/src/styles/tokens.css`. Add vitest-axe and keyboard tests in `apps/web/src/a11y/primitives.test.tsx`
- [ ] T026 Implement authentication and session handling. `apps/web/src/auth/supabase.ts` creates the Supabase JS client with `sessionStorage`, auto refresh and no `localStorage`. `apps/web/src/auth/SignIn.tsx` is labeled email/password sign-in with `autocomplete` tokens and errors that don't reveal whether an account exists. `apps/web/src/auth/session.ts` provides router guards that return to the validated target URL, plus a teardown on sign-out, account switch, refresh failure or 401 that cancels queries, clears the Query cache, tears down Realtime and clears search, cursors and account-specific URL state. Add tests in `apps/web/src/auth/session.test.tsx`, including vitest-axe on the sign-in states
- [ ] T027 Implement the profile settings for display name and IANA time zone in `apps/web/src/auth/ProfileSettings.tsx`. Save through the Supabase Auth user-update API, offer the browser-detected zone at first sign-in, report a rejected zone visibly, and use a labeled native `select` or Combobox. Add tests in `apps/web/src/auth/ProfileSettings.test.tsx`
- [ ] T028 [P] Add test profiles and commands. Put the Java 25 PostgreSQL-backed JUnit/Spring integration profiles in `pom.xml`, the Vitest/Testing Library/vitest-axe and Playwright (Chromium, Firefox and WebKit) scripts in `package.json`, and the `test-unit`, `test-contract`, `test-e2e` and `test-performance` services in `compose.yaml`. Add the shared Playwright helpers `tests/e2e/support/a11y.ts` (runs `@axe-core/playwright` with WCAG 2.1 A/AA tags and fails on any violation) and `tests/e2e/support/accounts.ts` (signs in the demo accounts)
- [ ] T029 [P] Create the evidence logs `specs/001-task-delegation/evidence/accessibility.md` and `docs/security/threat-review.md`, each with one section per story checkpoint and one for the release review. Accessibility sections record the date, browser and assistive-technology versions, the workflows covered, the findings, their dispositions and the reviewer. Security sections record the date, the trust boundaries and abuse cases reviewed, the evidence (tests or manual probes), the findings and the reviewer, with each finding also entered in `docs/security/findings-register.md`. Evidence files use headings and tables only, never `- [ ]` checkbox syntax, so `/speckit-implement` does not treat them as checklist gates

**Checkpoint**: `docker compose up --wait` reaches healthy status, `identity-reconcile --check`
reports no drift, demo accounts can sign in and out, and gateway and identity JUnit suites pass.

---

## Phase 3: User Story 1 - Delegate and Complete a Task (Priority: P1) 🎯 MVP

**Goal**: Let active employees privately create, assign, update, progress, reopen, cancel and
request deadline changes for tasks.

**Independent Test**: Using the creator, assignee, replacement and outsider accounts, create and
find an assigned task. Verify field, status and deadline rules, history, reassignment revocation,
deadline-request decisions and denied outsider access, completing every step by keyboard
with zero axe violations.

### Tests for User Story 1

- [ ] T030 [P] [US1] Add PostgreSQL JUnit tests for task constraints, identity-generated IDs, rollback sequence gaps, generated-key retrieval, stale versions, task-lock serialization and runtime-role DDL denial in `services/task-api/src/test/java/com/example/tasks/persistence/TaskRepositoryPostgresTest.java`
- [ ] T031 [P] [US1] Add JUnit service tests in `services/task-api/src/test/java/com/example/tasks/service/TaskServiceTest.java`. Cover the lifecycle matrix from data-model.md; creator-only edits; self-assigned direct deadline editing; deadline removal suppressing reminders and superseding requests (FR-025); date-only conversion to 23:59 in the creator's directory zone, including DST gaps and overlaps; past-deadline confirmation on create, edit, approval and reopen (FR-011); cancellation; and every deadline-request transition
- [ ] T032 [P] [US1] Add Spring JUnit API integration tests in `services/task-api/src/test/java/com/example/tasks/api/TaskApiIntegrationTest.java`. Cover creator and assignee access; outsider 404 versus disallowed-operation 403; inactive-user rejection with a valid JWT; idempotency replay and conflict; ETag/If-Match; field errors; and guessed neighboring IDs and IDs above 2^53-1
- [ ] T033 [P] [US1] Add vitest-axe and interaction tests for the task form, details and deadline-request states in `apps/web/src/features/tasks/TaskForm.test.tsx` and `apps/web/src/features/tasks/TaskDetails.test.tsx`. Cover error-summary focus, combobox announcements, the past-deadline confirmation dialog, reason dialogs, conflict notices, and a pending mutation never shown as saved
- [ ] T034 [P] [US1] Add Playwright acceptance coverage in `tests/e2e/delegate-complete-task.spec.ts` for create, assign, find, edit, status changes, blocked reason, reopen, cancellation, deadline requests and three-account privacy. Complete the whole journey keyboard-only and run `tests/e2e/support/a11y.ts` on every page and dialog in Chromium, Firefox and WebKit

### Implementation for User Story 1

- [ ] T035 [US1] Create the task core migration `services/task-api/src/main/resources/db/migration/V<next>__task_core.sql`, and quote these constraints exactly:
  - **Task**: `title` "trimmed 1..200 characters"; `description` "0..20,000 characters"; `priority` "`low|normal|high`"; `status` "`todo|in_progress|blocked|done|cancelled`"; `due_at` and `due_time_zone` "both present or both absent"; `blocked_reason`/`cancellation_reason` "1..2,000 when applicable"; immutable `creator_id`; and a monotonically increasing `version`.
  - **DeadlineRequest**: states "`pending|approved|declined|withdrawn|superseded`" with a "unique partial index on task where state=pending".
  - **ActivityEvent**: immutable, with allowlisted `changes`.
  - **MutationReceipt**: "unique `(actor_id,operation,idempotency_key)`", retained "at least seven days".
  - Indexes on creator and assignee with deadline and status, plus `(created_at,id)`.
- [ ] T036 [P] [US1] Create the task, deadline-request, activity-event and mutation-receipt domain types, mirroring the migration's constraints, in `services/task-api/src/main/java/com/example/tasks/domain/`
- [ ] T037 [P] [US1] Implement the task and deadline-request repositories in `services/task-api/src/main/java/com/example/tasks/persistence/`, with task-first `FOR UPDATE` locking, `RETURNING` generated keys, stable `(created_at,id)` ordering and no sequence reseeding
- [ ] T038 [P] [US1] Implement the read-only directory access in `services/task-api/src/main/java/com/example/tasks/directory/DirectoryRepository.java`. Search active users by case-insensitive name or email with trimmed `1..200`-character queries; return display name and email only; look up active state and time zone; and give distinct responses for empty queries (422), no matches (empty) and failures (503)
- [ ] T039 [US1] Implement task creation, creator-only field edits and reassignment, status transitions, blocked reasons, completion and reopen, cancellation and version checks in `services/task-api/src/main/java/com/example/tasks/service/TaskService.java`
- [ ] T040 [US1] Implement deadline handling in `services/task-api/src/main/java/com/example/tasks/service/DeadlineService.java`: parsing, date-only conversion to 23:59 in the creator's directory `time_zone` or an explicit `dueTimeZone`, rejection of ambiguous or nonexistent DST times, overdue computation, deadline removal, and past-deadline confirmation
- [ ] T041 [US1] Implement deadline-request submission, withdrawal, creator approval and decline, pending uniqueness, supersession and stale-decision conflicts in `services/task-api/src/main/java/com/example/tasks/service/DeadlineRequestService.java`
- [ ] T042 [US1] Persist allowlisted immutable history and atomic mutation receipts in the same transaction as task changes, in `services/task-api/src/main/java/com/example/tasks/service/TaskMutationTransaction.java`
- [ ] T043 [US1] Implement `/api/v1/users` and the task create, list, detail, edit, status, reopen, cancel and deadline-request endpoints from `contracts/http-api.md`, with contract-compliant 404/403/409/422 problem responses, in `services/task-api/src/main/java/com/example/tasks/api/TaskController.java`
- [ ] T044 [US1] Implement DTO serialization in `services/task-api/src/main/java/com/example/tasks/api/TaskDtoMapper.java`: decimal-string IDs, UUID user IDs, per-user permissions, ETags, version strings, nullable deadlines with zone, and computed `isOverdue`
- [ ] T045 [US1] Add the history pagination endpoint `GET /api/v1/tasks/{id}/history` in `services/task-api/src/main/java/com/example/tasks/api/TaskHistoryController.java`
- [ ] T046 [P] [US1] Implement the generated-client task, user and history query hooks in `apps/web/src/features/tasks/api.ts`, with AbortSignal cancellation, user-scoped keys and targeted invalidation
- [ ] T047 [US1] Implement the create/edit task form in `apps/web/src/features/tasks/TaskForm.tsx` with TanStack Form. Include the assignee Combobox, visible labels with required fields marked in text, priority, optional deadline and zone, server field errors through ErrorSummary, dirty-state preservation, idempotent retry and an explicit past-deadline confirmation Dialog
- [ ] T048 [US1] Implement task details and status controls in `apps/web/src/features/tasks/TaskDetails.tsx`. Use buttons for status actions; Dialogs with labeled required text areas for Blocked and Cancel reasons; status, overdue and No deadline shown as text; role-based field editing; polite announcements for the user's own outcomes; and a focusable conflict notice with Reload
- [ ] T049 [US1] Implement basic Assigned to me and Created by me lists in `apps/web/src/features/tasks/TaskList.tsx`. Use a semantic `table` with a `caption` and column headers; give each row a title link; and show creator, assignee, priority, status and deadline or No deadline as text, never color alone
- [ ] T050 [US1] Implement the deadline-request form and the creator's Approve, Decline and Withdraw buttons (each naming its request) in `apps/web/src/features/tasks/DeadlineRequestPanel.tsx`, using current task versions and keeping form state on errors
- [ ] T051 [US1] Implement the chronological history in `apps/web/src/features/tasks/TaskHistory.tsx` as an ordered list showing actor, time, and previous and new values as text, for creation, field and status changes, reassignment and deadline-request transitions
- [ ] T052 [US1] Record the US1 checkpoint keyboard-only and screen-reader pass (VoiceOver with Safari or NVDA with Firefox) over quickstart scenarios 1, 2 and 9 in `specs/001-task-delegation/evidence/accessibility.md`. Record the US1 security review in `docs/security/threat-review.md`, covering forged, expired and wrong-audience JWTs, API keys used as user tokens, inactive users with valid JWTs, outsider 404s, guessed neighboring IDs, idempotency-key reuse with a changed body, spoofed forwarding and identity headers, rate limits, and sign-in error disclosure. Resolve or register every defect and finding

**Checkpoint**: The P1 delegation increment works and is accessible without comments,
files, advanced list navigation or notification delivery.

---

## Phase 4: User Story 2 - Discuss Work and Share Files (Priority: P2)

**Goal**: Let current participants exchange append-only comments and upload and download
validated private files, preserving history and access revocation.

**Independent Test**: On a US1 task, add comments as both participants and upload and download
each supported format. Verify ordering, validation failures, partial-upload cleanup, outsider
denial, former-assignee denial on a copied download URL, and keyboard and axe compliance.

### Tests for User Story 2

- [ ] T053 [P] [US2] Add JUnit tests in `services/task-api/src/test/java/com/example/tasks/attachments/AttachmentServiceTest.java` for comment validation and order, upload idempotency, format and content mismatches, the 10,000,000-byte limit, bounded DOCX/XLSX archive parsing (including zip bombs), interrupted staging, cleanup and authorization recheck
- [ ] T054 [P] [US2] Add PostgreSQL/Spring integration tests in `services/task-api/src/test/java/com/example/tasks/api/TaskContentIntegrationTest.java` for atomic comment and file history, outbox-ready finalization, current-participant reads of historical content, and former-assignee denial on new GET and Range requests
- [ ] T055 [P] [US2] Add vitest-axe and interaction tests in `apps/web/src/features/tasks/CommentThread.test.tsx` and `apps/web/src/features/tasks/AttachmentPanel.test.tsx` for the composer label and limit, text and focus kept after a failed submission, the labeled file input naming the allowed formats and size, the progress accessible name, and announced rejection reasons
- [ ] T056 [P] [US2] Add Playwright coverage in `tests/e2e/discuss-and-share-files.spec.ts` for participant comments, supported, unsupported and oversized files, retry behavior and copied attachment-link revocation, keyboard-only and with `tests/e2e/support/a11y.ts` on each state

### Implementation for User Story 2

- [ ] T057 [US2] Create the comments and attachments migration `services/task-api/src/main/resources/db/migration/V<next>__comments_attachments.sql`, quoting these constraints:
  - **Comment**: `text` "trimmed nonempty, <=10,000 characters", append-only.
  - **Attachment**: `size_bytes` "1..10,000,000"; `state` "`staging|ready|failed`"; a unique `upload_operation_id`; and an opaque `object_key`.
  - Ready-only visibility and `(task_id, created_at, id)` indexes.
- [ ] T058 [US2] Implement participant-authorized comment creation (allowed on Done and Cancelled tasks) and chronological `(created_at,id)` retrieval with plain-text validation in `services/task-api/src/main/java/com/example/tasks/service/CommentService.java`
- [ ] T059 [US2] Implement streaming validation in `services/task-api/src/main/java/com/example/tasks/attachments/AttachmentValidationService.java`: a 10,000,000-byte limit, content and extension matching for PDF/PNG/JPEG/TXT/DOCX/XLSX, TXT text validation, and bounded DOCX/XLSX archive inspection
- [ ] T060 [US2] Implement the upload flow in `services/task-api/src/main/java/com/example/tasks/attachments/AttachmentService.java`: idempotent private staging and finalization with no database transaction held while streaming, an access recheck at finalization, and an atomic commit of ready metadata, history and outbox
- [ ] T061 [US2] Implement a bounded cleanup job for abandoned staged objects, with visible operational errors and metrics, in `services/task-api/src/main/java/com/example/tasks/attachments/StagingCleanupJob.java`
- [ ] T062 [US2] Implement the comment, attachment-metadata and authorized streaming/Range download endpoints in `services/task-api/src/main/java/com/example/tasks/api/TaskContentController.java`, with `Cache-Control: no-store`, `nosniff`, a sanitized attachment Content-Disposition, and no storage redirects
- [ ] T063 [US2] Implement the comment composer and chronological thread in `apps/web/src/features/tasks/CommentThread.tsx`. Use a labeled textarea with an announced limit and an ordered list showing author and time; keep text and focus after failures and announce the outcome on success
- [ ] T064 [US2] Implement the attachment list in `apps/web/src/features/tasks/AttachmentPanel.tsx`: a labeled native file input, an accessibly named `progress`, retry, persistent announced rejection messages, and authenticated download controls
- [ ] T065 [US2] Extend `apps/web/src/features/tasks/TaskHistory.tsx` to render comment and attachment-addition events with actor and time
- [ ] T066 [US2] Record the US2 checkpoint keyboard and screen-reader pass over quickstart scenarios 1 and 4 in `specs/001-task-delegation/evidence/accessibility.md`. Record the US2 security review in `docs/security/threat-review.md`, covering upload format spoofing, oversized files and zip bombs, interrupted streams, filename and Content-Disposition injection, `nosniff`/`no-store` headers, and copied links and Range requests after revocation. Resolve or register every defect and finding

**Checkpoint**: Comments and private attachments work for current participants, every new
download rechecks authorization, and the workflows pass accessibility checks.

---

## Phase 5: User Story 3 - Find and Prioritize Personal Work (Priority: P2)

**Goal**: Support fast personal task discovery with composable search, filter and sort; resilient
refresh; URL-backed non-text state; and per-user table preferences.

**Independent Test**: Populate both lists with mixed statuses, priorities, assignees and dates,
including undated tasks. Verify combined filters and search, stable deadline sort, paging, URL
Back/Forward, account isolation, keyboard column controls, refresh within 15 seconds without
lost edits, access-denied clearing, and silent background refresh for screen readers.

### Tests for User Story 3

- [ ] T067 [P] [US3] Add PostgreSQL JUnit repository tests in `services/task-api/src/test/java/com/example/tasks/persistence/TaskSearchRepositoryTest.java` for AND-combined filters, viewer-zone inclusive date ranges translated to half-open instants, no-deadline exclusion, stable keyset cursors and null-last sorting in both directions
- [ ] T068 [P] [US3] Add frontend tests in `apps/web/src/features/tasks/TaskListSearch.test.tsx` for validated filter URLs, the visible error on invalid or contradictory parameters, Back/Forward restoration, cursor reset, free-text exclusion from URLs and account-switch clearing
- [ ] T069 [P] [US3] Add frontend tests in `apps/web/src/features/tasks/TaskTablePreferences.test.tsx` for the column-preferences dialog: visibility checkboxes and Move earlier/Move later buttons by keyboard, Title's disabled checkbox with its explanation, reset, the storage-failure and corruption notices, user, list and browser isolation, and vitest-axe
- [ ] T070 [P] [US3] Add Playwright coverage in `tests/e2e/find-and-prioritize-work.spec.ts` for search, filter, sort and Load more; refresh freshness within 15 seconds; dirty-form preservation; content cleared on access denied; background refresh not moving focus; account switching; and reflow at 320 CSS px and 200% zoom, using `tests/e2e/support/a11y.ts`

### Implementation for User Story 3

- [ ] T071 [US3] Implement bounded keyset pagination and allowlisted composable status, priority, assignee, inclusive date, no-deadline and case-insensitive title/description filters, with stable `(due_at nulls last, created_at, id)` tie breaks, in `services/task-api/src/main/java/com/example/tasks/persistence/TaskSearchRepository.java`
- [ ] T072 [US3] Validate list-query combinations (noDeadline is mutually exclusive with dueFrom/dueTo), bind filters and sort to opaque cursors, and enforce a default limit of 25 and a maximum of 100 in `services/task-api/src/main/java/com/example/tasks/api/TaskListQuery.java`
- [ ] T073 [US3] Create the SC-005 seed fixture `tests/performance/fixtures/seed-sc005.sh`, run as a `seed-performance` tools service in `compose.yaml`: 50 synthetic users with 1,000 accessible tasks each, mixed statuses, priorities, assignees and deadlines (including undated tasks), deterministic and non-sensitive. Measure query plans against it, then add the selected search indexes (for example `pg_trgm`) with the measurements recorded in a comment in `services/task-api/src/main/resources/db/migration/V<next>__task_search_indexes.sql`
- [ ] T074 [US3] Implement Router-validated URL state for view, status, priority, assignee, date or no-deadline filters and sort, keeping free-text search and cursors in memory, in `apps/web/src/features/tasks/task-list-route.tsx`
- [ ] T075 [US3] Implement manual server-side filtering, sorting and pagination in `apps/web/src/features/tasks/TaskTable.tsx`: a `caption`, `aria-sort` on the sorted column, filters in a labeled `form` with Apply, and a Load more button that moves focus to the first new row. Show empty-result and load-failure states as distinct text, and never reorder rows under keyboard focus during refresh
- [ ] T076 [US3] Implement column visibility and order preferences scoped to the verified user UUID and list type in `localStorage`, in `apps/web/src/features/tasks/TaskTablePreferences.ts` and the `apps/web/src/features/tasks/ColumnPreferencesDialog.tsx` dialog. Keep Title visible, store only allowlisted column IDs, and report invalid or unavailable storage as persistent text
- [ ] T077 [US3] Configure the query policy in `apps/web/src/features/tasks/query-policy.ts`: 10-second active polling, focus and reconnect refetch, bounded memory-only pages, a stale-data notice on failure, preserved dirty form and comment state, silent background updates with no announcements, and clearing of cached task content with focus on the access-denied heading after a 404 or 403 (FR-026)
- [ ] T078 [US3] Record the US3 checkpoint keyboard and screen-reader pass over quickstart scenarios 6 and 7, including 200% zoom and 320 CSS px reflow, in `specs/001-task-delegation/evidence/accessibility.md`. Record the US3 security review in `docs/security/threat-review.md`, covering tampered or replayed cursors, contradictory or injected URL parameters, copied URLs opened by another account, search-query logging, and browser storage holding nothing but column preferences. Resolve or register every defect and finding

**Checkpoint**: Personal lists remain authorized, responsive and accessible across search,
navigation, refresh and account changes.

---

## Phase 6: User Story 4 - Stay Informed Without Chasing Updates (Priority: P3)

**Goal**: Deliver private durable in-app notifications, applicable reminders, auditable history,
replaceable optional email, and generic private Realtime hints.

**Independent Test**: Generate assignment, task-change, comment, attachment, deadline-request,
due-soon and overdue events; stop and restart the worker; reassign users; change deadlines; toggle
email; and reconnect Realtime. Verify exactly-once inbox effects, correct current recipients,
suppression of obsolete work, generic payloads, recovery within five minutes, and an accessible
inbox.

### Tests for User Story 4

- [ ] T079 [P] [US4] Add JUnit tests in `services/task-api/src/test/java/com/example/tasks/delivery/OutboxReminderServiceTest.java`, using a controllable clock, for outbox leases, fencing tokens, bounded retries, reminder-identity restoration, self-assignment deduplication and obsolete-reminder suppression
- [ ] T080 [P] [US4] Add the SC-004 matrix in `services/task-api/src/test/java/com/example/tasks/delivery/ReminderMatrixTest.java`: 100 parameterized controllable-clock cases covering rescheduling, restoring a previously used deadline, completion, reopening, reassignment, self-assignment, deadline removal, worker downtime and repeated delivery attempts, asserting each eligible reminder is generated within five minutes of its trigger with zero duplicate or unauthorized deliveries
- [ ] T081 [P] [US4] Add JUnit/PostgreSQL tests in `services/notifications/src/test/java/com/example/notifications/service/NotificationConsumerPostgresTest.java` for event deduplication, payload-hash conflicts, inbox uniqueness, recipient authorization, read state and delivery-work recovery
- [ ] T082 [P] [US4] Add JUnit tests in `services/notifications/src/test/java/com/example/notifications/delivery/EmailDeliveryServiceTest.java` for policy epochs, disabled-period suppression, stable idempotency keys, ambiguous SMTP outcomes and no automatic replay of unknown states
- [ ] T083 [P] [US4] Add Spring integration tests in `services/notifications/src/test/java/com/example/notifications/api/NotificationIntegrationTest.java` for access checks, the outbox claim, ack, retry and resolve contracts, five-second timeouts, failing closed with 503, and visible dependency failures
- [ ] T084 [P] [US4] Add Realtime integration tests in `tests/contract/realtime-authorization.spec.ts` for receive-only access to the user's own private topic; denial of wrong, anonymous and inactive topics, client sends and public channels; token refresh; and gateway WebSocket recovery
- [ ] T085 [P] [US4] Add vitest-axe and interaction tests in `apps/web/src/features/notifications/NotificationInbox.test.tsx` for an inbox of links, read and unread state as text, Mark read buttons naming their notification, the badge's accessible name with the count, and announcements rate-limited to one per 10 seconds
- [ ] T086 [P] [US4] Add crash and restart end-to-end coverage in `tests/e2e/notification-recovery.spec.ts`, proving durable inbox recovery within five minutes, no duplicate notifications, stale-reminder suppression and inbox operation during a Realtime outage, with `tests/e2e/support/a11y.ts` on the inbox

### Implementation for User Story 4

- [ ] T087 [US4] Create the outbox migration `services/task-api/src/main/resources/db/migration/V<next>__task_outbox_reminders.sql`, quoting these constraints:
  - **TaskOutbox**: `state` "`pending|leased|acknowledged|failed`", with claim token, lease and attempt columns.
  - **ReminderIdentity**: "unique `(task_id,recipient_id,reminder_type,due_at)`".
  - Operational failure records.
- [ ] T088 [P] [US4] Create the first notifications migration `services/notifications/src/main/resources/db/migration/V<next>__notification_delivery.sql`, quoting these constraints:
  - **ConsumedEvent**: "unique source `event_id`" with a payload hash.
  - **Notification**: "Unique `(source_event_id,recipient_id)`".
  - **DeliveryWork**: `channel` "`realtime_hint|email`"; `state` "`pending|leased|sent|failed|suppressed|unknown`"; "Unique `(notification_id,channel)`".
  - **DeliveryPolicy**: email disabled by default, with a monotonic `epoch` and policy-history intervals.
- [ ] T089 [US4] Implement task-owned event envelopes, created in the same transaction as task and history changes, that exclude private task text and actor recipients, in `services/task-api/src/main/java/com/example/tasks/delivery/TaskOutboxService.java`
- [ ] T090 [US4] Implement the internal `/internal/v1` outbox claim, ack, retry and resolve endpoints, plus reminder evaluation and authorization checks, with batches of at most 100 and claim-token fencing, in `services/task-api/src/main/java/com/example/tasks/api/InternalDeliveryController.java`
- [ ] T091 [US4] Implement due-soon and overdue scheduling at 24 hours before and at the deadline, with evaluation at least every 30 seconds, catch-up against current task state, and requeue only when no inbox delivery was committed, in `services/task-api/src/main/java/com/example/tasks/delivery/ReminderScheduler.java`
- [ ] T092 [US4] Implement transactional event consumption, payload-hash validation, retention of recipient outcomes, inbox deduplication and durable delivery-work creation in `services/notifications/src/main/java/com/example/notifications/service/NotificationConsumer.java`
- [ ] T093 [US4] Implement leased delivery workers with persisted claim tokens, bounded exponential backoff, failure retention after ten attempts, source acknowledgments and explicit safe replay in `services/notifications/src/main/java/com/example/notifications/worker/NotificationWorker.java`
- [ ] T094 [US4] Implement the notification list, unread count and read-state endpoints, with bounded current-task authorization batches that fail closed with 503, in `services/notifications/src/main/java/com/example/notifications/api/NotificationController.java`
- [ ] T095 [US4] Implement email delivery in `services/notifications/src/main/java/com/example/notifications/delivery/EmailDeliveryService.java`: policy epochs, a disabled-by-default capture-only SMTP adapter, generic templates with no task text, current-access checks, and accepted, retryable, rejected and unknown outcomes
- [ ] T096 [US4] Add the Realtime policy migration in `infra/supabase/migrations/V<next>__realtime_private_inbox.sql`, applied by `db-bootstrap`. Use a private-only tenant, a receive-only SELECT policy on `realtime.messages` that binds `realtime.topic() = 'user:' || auth.uid()`, `extension='broadcast'` and `identity.is_active(auth.uid())`, and add no INSERT or Presence policies
- [ ] T097 [US4] Implement server-only Broadcast publishing through Supabase's internal gateway in `services/notifications/src/main/java/com/example/notifications/delivery/RealtimePublisher.java`. Make `private:true` mandatory, send a generic `inbox.changed` payload containing only the `signalId`/`occurredAt` from the DeliveryWork ID, redact credentials, and recheck activity before each publish
- [ ] T098 [US4] Implement the frontend Realtime client in `apps/web/src/features/notifications/realtime.ts`: the private receive-only `user:{auth UUID}` channel lifecycle, token refresh and teardown registered with the session teardown, subscribe-before-fetch recovery, 10-second fallback polling and inbox query invalidation
- [ ] T099 [US4] Implement the notification inbox in `apps/web/src/features/notifications/NotificationInbox.tsx`: a list of links, read and unread state as text, Mark read buttons, an unread badge with an accessible name, and new-count announcements through LiveRegion rate-limited to one per 10 seconds
- [ ] T100 [US4] Implement the operator email-policy command in `scripts/delivery-policy.sh`, exposed as a `delivery-policy` tools service in `compose.yaml` and authenticated with operator credentials, supporting `email enable-capture` and `email disable` and recording actor and time
- [ ] T101 [US4] Record the US4 checkpoint keyboard and screen-reader pass over quickstart scenario 3 and the inbox in `specs/001-task-delegation/evidence/accessibility.md`. Record the US4 security review in `docs/security/threat-review.md`, covering public `/internal/**` bypass attempts, missing, wrong-scope and replayed internal credentials, Realtime other-user, anonymous and inactive topics, client sends and public channels, generic Broadcast and email payloads, publisher-key exposure, and inbox access after reassignment. Resolve or register every defect and finding

**Checkpoint**: Task actions succeed during notification outages, inbox work recovers durably,
Realtime and email never carry authorization or task content, and the inbox is accessible.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Validate the complete local demo, security gates, operational recovery,
accessibility and measurable outcomes.

- [ ] T102 [P] Document the prerequisites (native Docker on Linux, Docker Desktop or a compatible engine on macOS including Apple Silicon, and the Windows WSL fallback), setup, startup, health, demo flows, email capture, safe restart and limitations, with POSIX and PowerShell commands, in `README.md`
- [ ] T103 Finalize `compose.yaml`: Supabase, then `db-bootstrap`, then the migration jobs, then the runtime services; completion checks for every job; health dependencies that separate required services from optional email and live delivery; persistent storage; loopback-only admin bindings; and the tools, test, security and email-capture profiles
- [ ] T104 [P] Add k6 scenarios in `tests/performance/task-management.js` that reuse the T073 seed fixture, for 50 concurrent employees and the two-second p95 list, search and detail threshold
- [ ] T105 [P] Add the CI pipeline in `.github/workflows/ci.yml`: Java and TypeScript builds; JUnit for the gateway, task API, notifications and identity tools; PostgreSQL integration; Vitest with vitest-axe and Playwright with axe in Chromium, Firefox and WebKit; `scripts/check-migrations.sh`; multi-arch image builds; secret detection (Gitleaks); source analysis (Semgrep); Maven and npm dependency scans; and Trivy image scans of all pinned runtime images. Fail on test failures and never silently skip a required suite
- [ ] T106 [P] Add the scanner profiles that write SARIF/JSON to the ignored `reports/security/` directory, the tracked `docs/security/findings-register.md` (ID, scanner and version, severity, component and package or image digest, evidence, disposition, and for exceptions the rationale, owner, expiry and mitigation), and `scripts/release-gate.sh`, which fails on missing or failed scans, a register stale for the current revision, or unresolved High/Critical findings without an unexpired exception
- [ ] T107 Consolidate the story-checkpoint security reviews (T052, T066, T078, T101) and perform the release security review of every trust boundary and abuse case listed in plan.md (forged and expired tokens, header spoofing, `/internal/**` bypass, runtime-role DDL, Realtime topic abuse, upload spoofing and zip bombs, copied links after revocation, guessed IDs, deactivated users, metadata-driven activation) in `docs/security/threat-review.md`
- [ ] T108 First confirm the Docker daemon and Compose are usable (`docker info`, `docker compose version`); on Windows, if they fail, check WSL distributions for a working daemon before declaring validation blocked, without installing or reconfiguring anything (constitution II). Then run the full quickstart acceptance matrix, including Compose recreation persistence, sequence IDs above 2^53-1, all user journeys, scenarios 9 and 10, worker crashes, the reminder matrix, Realtime authorization, disabled-email recovery and `release-gate`. Record the commands, results and blocked or unexecuted checks in `specs/001-task-delegation/evidence/validation.md`
- [ ] T109 Verify the implementation against every requirement from FR-001 to FR-031 and every criterion from SC-001 to SC-017, record failed or unavailable validation gates, and resolve outstanding cross-service contract gaps in `specs/001-task-delegation/evidence/verification.md`
- [ ] T110 Complete and record the release keyboard and screen-reader review across the create/assign, task-update, comment/attachment, list-navigation, customization, notification and sign-in/profile workflows in Chromium, Firefox and Safari, covering 200% zoom and 320 CSS px reflow, in `specs/001-task-delegation/evidence/accessibility.md`. Unresolved defects block release (SC-017)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 creates the paths. T002-T009 can then run in parallel. T010 depends on T006.
- **Foundational (Phase 2)**: Depends on Setup.
  - T012 depends on T006, T007 and T011.
  - T013 and T014 depend on T002, T011 and T012.
  - T015 depends on T012.
  - T016, T017 and T023 depend on T002 and T015.
  - T020 depends on T018 and T019.
  - T021 and T022 depend on T002 and T016's JWT rules.
  - T024 and T025 depend on T003.
  - T026 and T027 depend on T024 and T025.
  - Phase 2 blocks all stories.
- **User Stories (Phase 3+)**: US1 depends on the foundation. US2 and US3 depend on US1's task identity, authorization and task APIs. US4 depends on US1's mutation and history events and on US2's comment and file event sources; it does not depend on US3.
- **Polish (Phase 7)**: T102, T104, T105 and T106 can start once the foundation exists. T103 and T107-T110 depend on all implemented stories.

### User Story Dependencies

- **US1 (P1)**: Starts after Phase 2. MVP core.
- **US2 (P2)**: Depends on US1 task records and participant authorization.
- **US3 (P2)**: Depends on US1 task records and the list API. It can run in parallel with US2.
- **US4 (P3)**: Depends on US1 and US2 event sources. It is independent of US3.

### Within Each Story

- Write the tests first and confirm they fail before implementing.
- Then build in this order: migration, domain, repository, service, controller, frontend hooks, then UI.
- The story's checkpoint task (T052, T066, T078, T101), covering both the accessibility pass and the security review, is the last task in its phase and gates the story checkpoint.
- Because migration versions are assigned at merge time, parallel US2 and US3 work cannot collide on version numbers; whichever merges first takes the next integer.

### Parallel Opportunities

- **Setup**: T002-T009 after T001.
- **Foundation**: T011, T013, T016, T018, T019, T022, T023, T025, T028 and T029 in separate files.
- **US1**: tests T030-T034 in parallel; T036, T037, T038 and T046 in parallel after T035.
- **US2**: tests T053-T056 in parallel; T058 and T059 are separable before T060 and T062.
- **US3**: can run alongside US2. Tests T067-T070 in parallel; backend T071-T073 and frontend T074-T077 are separable after the query contract.
- **US4**: tests T079-T086 in parallel; T087 and T088 in parallel; task-side T089-T091 and notification-side T092-T097 are separately owned.
- **Polish**: T102, T104, T105 and T106 in parallel.

### Parallel Examples by User Story

```text
US1 after T035 merges:
Task: T030 services/task-api/src/test/java/com/example/tasks/persistence/TaskRepositoryPostgresTest.java
Task: T031 services/task-api/src/test/java/com/example/tasks/service/TaskServiceTest.java
Task: T033 apps/web/src/features/tasks/TaskForm.test.tsx and TaskDetails.test.tsx
Task: T038 services/task-api/src/main/java/com/example/tasks/directory/DirectoryRepository.java
Task: T046 apps/web/src/features/tasks/api.ts

US2 after US1 authorization is available:
Task: T053 services/task-api/src/test/java/com/example/tasks/attachments/AttachmentServiceTest.java
Task: T055 apps/web/src/features/tasks/CommentThread.test.tsx and AttachmentPanel.test.tsx
Task: T059 services/task-api/src/main/java/com/example/tasks/attachments/AttachmentValidationService.java

US3 alongside US2:
Task: T067 services/task-api/src/test/java/com/example/tasks/persistence/TaskSearchRepositoryTest.java
Task: T069 apps/web/src/features/tasks/TaskTablePreferences.test.tsx
Task: T074 apps/web/src/features/tasks/task-list-route.tsx

US4 after the outbox contract is stable:
Task: T080 services/task-api/src/test/java/com/example/tasks/delivery/ReminderMatrixTest.java
Task: T081 services/notifications/src/test/java/com/example/notifications/service/NotificationConsumerPostgresTest.java
Task: T084 tests/contract/realtime-authorization.spec.ts
Task: T088 services/notifications/src/main/resources/db/migration/V<next>__notification_delivery.sql
```

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1 and Phase 2, especially the bootstrap and migration jobs, identity sync, JWT validation, gateway, sign-in and accessible primitives.
2. Complete US1 (T030-T052), including its accessibility checkpoint.
3. Validate US1 independently with the four demo accounts, using PostgreSQL-backed JUnit tests and keyboard-only Playwright runs with axe.
4. Stop at the P1 checkpoint before adding comments, files, advanced lists or notification delivery.

### Incremental Delivery

1. Deliver Setup and Foundational.
2. Deliver US1 as the MVP.
3. Add US2 (comments and files) and US3 (search, URL state, preferences) in parallel.
4. Add US4 (durable inbox, reminders, email capture, Realtime hints).
5. Run Polish: Compose hardening, CI, the security register and review, performance, the acceptance matrix, verification and the release accessibility review.

### Parallel Team Strategy

After the foundation, one implementer extends US1 while another prepares US2 tests and storage
and a third builds US3 against the agreed task API. US4 starts after the US1 outbox and the US2
event sources are stable; task-side and notification-side work are separately owned.

## Notes

- Every task uses the `- [ ] Tnnn [P?] [US?] Description with file path` format. Setup, Foundational and Polish tasks omit story labels.
- `checklists/requirements.md` remains the spec-quality checklist and is not used for implementation evidence. Evidence goes to `evidence/accessibility.md`, `evidence/validation.md`, `evidence/verification.md` and `docs/security/`, which use no checkbox syntax, so the only checklist `/speckit-implement` gates on is `checklists/requirements.md`.
- Removed from the previous task list: PowerShell host scripts (now POSIX scripts in containers), fixed migration version numbers, Java code under `infra/identity` (now the `tools/identity` module with tests), and roles created by service migrations (now `db-bootstrap`).
