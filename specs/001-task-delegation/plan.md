# Implementation Plan: Private Task Delegation

**Branch**: `main` (no feature branch; feature resolved through `.specify/feature.json`) |
**Date**: 2026-10-27 | **Revised**: 2026-10-28
**Spec**: [spec.md](spec.md)

**Input**: `specs/001-task-delegation/spec.md` (including FR-031/SC-017), constitution
v1.2.1, collaborative planning decisions recorded in [research.md](research.md), and the
2026-10-28 `/speckit-analyze` findings (accessibility, identity tests, migrations, tooling).

## Summary

Build a private, two-participant task-management demo with assignments, optional deadlines,
blocked reasons, deadline-change approval, cancellation, discussion, files, and history.
Use a React/Vite SPA with TanStack Router/Query/Form/Table, Spring Cloud Gateway, a Spring Boot task
API, and an independently deployed Spring Boot notification service/worker. Use self-hosted
Supabase Realtime private Broadcast instead of a custom Socket.IO service. Self-host Supabase Auth, PostgreSQL, and private Storage
with their required dependencies. Start the entire stack through Docker Compose.

The task API owns task rules and commits changes, history, and an outbox event atomically.
The notification service owns inboxes and delivery records and consumes a restricted task
integration API. Realtime emits only `inbox.changed` hints; persisted inboxes and HTTP
refresh provide recovery. No Redis, RabbitMQ, service registry, or distributed transaction
coordinator is required for this single-instance local demo.

## Technical Context

**Language/Version**: Java 25 LTS for all Java services and their build/test runtime; TypeScript with strict checking; Node.js 24 LTS for frontend build/test tooling only, not a custom live-delivery runtime.

**Primary Dependencies**: Spring Boot 4.1.2 on Java 25; compile with Java release 25 and run service/test containers on JDK 25. Spring Cloud 2025.1.x BOM at
2025.1.2 or later (the first service release supporting Boot 4.1; 2025.1.4 is current and
was rechecked against the official compatibility table on 2026-10-28);
Gateway Server WebFlux; Spring Security resource servers; Spring MVC/JDBC for business
services; Flyway; React; Vite; TanStack Router, Query, Form and Table; Supabase JS for Auth and Realtime.
Use Maven Wrapper with Java 25 toolchain/release configuration and npm workspaces/lockfile. Resolve and lock compatible frontend patches
and immutable container references at scaffolding; do not use floating image tags.

**Storage**: One self-hosted Supabase PostgreSQL instance with separately owned identity
directory, task, and notification schemas; private Supabase Storage bucket and persistent
object/database volumes. No browser access to application schemas or bucket downloads.
Application primary keys use per-table PostgreSQL bigint identity sequences, not UUIDs;
preserve Supabase Auth UUIDs and retry/security tokens. Java Long IDs serialize as decimal
strings throughout HTTP/Broadcast/TypeScript to avoid precision loss; see data-model.md.

**Testing**: JUnit 5, Spring integration tests, PostgreSQL integration/contract tests,
Vitest/Testing Library, Playwright with isolated browser accounts, private Broadcast integration tests,
axe-core accessibility checks in frontend tests, and k6 performance scenarios. Semgrep,
dependency checks, Gitleaks, and Trivy cover the
constitution's security gates. Tests and scanners run in Compose tool profiles where useful.
Every Java service, including the Spring Cloud Gateway, includes JUnit unit and applicable
Spring integration cases, with bug-fix regression tests and PostgreSQL-backed persistence
tests where it owns database behavior. Gateway coverage exercises route allowlists,
authentication/rate-limit behavior, origin checks, and WebSocket upgrades. CI executes
applicable suites and fails on failures; frontend/E2E tests do not replace JUnit coverage.
Core user workflows also receive manual keyboard and screen-reader review; accessibility
defects are tracked and resolved before release (constitution v1.2.0). Accessibility is
verified per user story (axe-core in that story's component and Playwright suites plus a
keyboard pass at its checkpoint), not only in the release pass. The identity tools module
has its own JUnit unit and PostgreSQL-backed integration tests for directory sync and
reconciliation.

**Target Platform**: Local Linux containers through native Docker (Linux, or Docker Desktop
or a compatible engine on macOS) or Docker in Windows WSL; images and build stages MUST
support both `linux/amd64` and `linux/arm64`. Desktop Chromium, Firefox and Safari (current
major versions; Safari is the VoiceOver reference browser on macOS), responsive layout and keyboard-operable core workflows. Host prerequisites are Docker and Compose only;
build, setup, migration, test and scan tooling runs in containers with POSIX `sh` scripts,
and documented host commands are given for POSIX shells and PowerShell.
Production deployment, native mobile, multi-instance live-delivery scaling, and automatic failover are
not delivery targets. Use synthetic demo data only.

**Project Type**: Containerized web application with independently deployable services.

**Performance Goals**: Spec SC-001 through SC-017; 50 active employees, 1,000 accessible
tasks per employee, p95 list/search/detail under two seconds; active refresh within
15 seconds; applicable reminders and recovery catch-up within five minutes.
Use a 10-second active polling interval with focus/reconnect refresh. Normal notification
events target inbox persistence within five seconds at demo load; Broadcast invalidates
the inbox immediately after persistence. Reminder generation runs at least every 30 seconds.

**Constraints**: Local-only host bindings; no application business bypass through Supabase
REST or Storage; immediate authorization on new requests; no secrets in browser bundles,
logs, images, or tracked configuration. All user data persists through ordinary recreation.
Email disabled by default; optional capture-only SMTP profile, not real recipient delivery.
The browser keeps the Supabase session in tab-scoped `sessionStorage`, never `localStorage`;
column preferences are the only `localStorage` data. Content-Security-Policy forbids inline
and third-party scripts to limit token exposure through XSS.

**Scale/Scope**: Single company; creator/current assignee only; one instance of each service.
Plan a development host with 16 GB available RAM, four CPU cores, and 80 GB available disk
as an initial whole-stack budget, not a measured guarantee. Record actual resource usage
during Compose smoke/load validation; Supabase's documented minimum is not a full-stack
capacity estimate.

## Constitution Check

*Pre-research and post-design gates: PASS for this design; execution evidence is deferred
to implementation, not claimed here.*

| Principle | Design evidence and implementation gate |
|-----------|------------------------------------------|
| Bounded microservices | Task ownership and notification ownership are distinct; gateway and Supabase Realtime have transport responsibilities. No direct cross-service business-table writes. Contracts define the only integration operations. Identity sync is identity-owned SQL plus a tools module (reconcile, demo provisioning), not a new service: Supabase Auth remains the identity service. |
| Reproducible containers | Root Compose includes frontend edge, Java services, required Supabase topology including Realtime, a privileged one-shot database bootstrap, per-service migration jobs, and optional tooling/capture profiles. No custom Node delivery service. Pin multi-arch images by digest; health-check dependencies; preserve volumes; document native Linux/macOS and WSL paths with POSIX and PowerShell commands. |
| Security/privacy | JWT validation and active-user checks in each boundary (gateway pre-check plus each service), least-privilege SQL roles created only by the bootstrap job, task authorization on every content/download request, local-only administration, private storage, tab-scoped session storage and strict CSP. Trust boundaries and abuse cases are listed under Validation and security gates. |
| Vulnerability checks | Source, dependency, secret, and image scans are required. Results feed a tracked findings register recording severity, component, evidence and disposition; a release gate fails on unresolved High/Critical findings without an unexpired, owner-approved exception. Scanner failures are incomplete, not passes. |
| Contracts/reliability | Versioned HTTP/event contracts, task/outbox transactions, leased claims, idempotent inbox insertion, push-as-hint semantics, bounded retries and recovery tests. JUnit unit/integration/regression cases in every Java module, including Gateway route/security/WebSocket tests and identity sync/reconcile tests; real PostgreSQL persistence tests and mandatory CI execution per v1.2.0. |
| Accessible user interfaces | WCAG 2.1 AA / EN 301 549 (FR-031, SC-017) is a design and per-story acceptance constraint defined in [contracts/ui-accessibility.md](contracts/ui-accessibility.md). Each story's UI work includes axe-core checks and keyboard criteria; each story checkpoint includes a keyboard and screen-reader pass; release validation repeats the manual review with defects tracked to resolution. |

Supabase is self-hosted here; the constitution's managed-service example is not a hosting
mandate. No governance exception is requested. SMTP capture is a demo integration only:
real email enablement is gated on an idempotency-capable adapter and delivery-failure tests.

The spec is technology-neutral as of 2026-10-28, and all 16 spec-checklist items pass. The
user-selected technology (self-hosted Supabase, Spring, React/Vite with TanStack, Docker
Compose, PostgreSQL) remains a binding constraint recorded here and in research.md, which
also keeps the original spec wording verbatim.

Governance follow-up outside this plan: `.specify/memory/constitution.md` was committed with
its temporary Sync Impact Report, which the constitution requires removing before commit.
This does not change any obligation used above; remove it in a separate constitution edit.

## Project Structure

### Documentation (this feature)

```text
specs/001-task-delegation/
  spec.md
  plan.md
  research.md
  data-model.md
  quickstart.md
  tasks.md                     regenerate with /speckit-tasks after this revision
  contracts/
    http-api.md
    delivery.md
    ui-accessibility.md
  checklists/
    requirements.md            spec-quality gate only
  evidence/                    accessibility, validation, verification logs (no checkboxes)
```

### Source Code (planned; not generated by this command)

```text
apps/
  web/                         React SPA, auth/session, accessible UI primitives
packages/
  api-client/                  TypeScript client generated from implemented OpenAPI
services/
  gateway/                     Spring Cloud Gateway, no business database
  task-api/                    Spring MVC, task rules, task-owned migrations
  notifications/               Spring MVC inbox API plus background workers
tools/
  identity/                    Java CLI module: directory reconcile, demo provisioning (JUnit)
infra/
  database/bootstrap/          Privileged one-shot: schemas, owner/runtime roles, grants
  identity/migrations/         Identity-owned directory projection, auth.users triggers
  supabase/                    Pinned Compose/config, Realtime tenant and receive-only policies
  edge/                        Unprivileged static/proxy config, CSP and security headers
  containers/                  Multi-arch Java and web Dockerfiles
tests/
  contract/
  e2e/
  performance/
scripts/                       POSIX sh entrypoints run inside tools containers
docs/security/                 Tracked findings register and accepted exceptions
compose.yaml
pom.xml, mvnw, mvnw.cmd        Java reactor; services and tools independently packaged
package.json                   npm workspaces for web/client
```

**Structure Decision**: One repository, distinct deployment units; shared packages contain
wire contracts/client generation, not shared persistence entities or business repositories.
Compose DNS supplies service discovery. Maven/npm tooling is pinned with wrappers/locks;
both `mvnw` and `mvnw.cmd` are committed and container builds use `./mvnw`. Repository
paths use forward slashes in all documents and configuration.

## Phase 0: Research Decisions

[research.md](research.md) records accepted choices, rejected alternatives, official sources,
and compatibility caveats. All design decisions needed for Phase 1 are resolved.

- React/Vite selected after identifying that TanStack Router does not support Vue.
- Java/Spring chosen over the proposed Node business backend.
- Spring gateway retained; user approved replacing Node Socket.IO with Supabase private Broadcast.
- TanStack Form handles client validation/dirty state/server errors; Table handles column preferences.
- PostgreSQL outbox selected over adding Redis/RabbitMQ.
- PostgreSQL bigint identity sequences selected over UUID application keys; Supabase user
  UUIDs remain unchanged and bigint wire IDs are strings.
- Supabase retains identity ownership; task management adds no enrollment requirement.
- Email disabled by default, capture-only optional; real delivery requires idempotency.
- Added 2026-10-28: identity sync via `auth.users` triggers plus a tested reconcile tool;
  time zone as a validated identity profile attribute; privileged bootstrap plus per-service
  migration jobs with merge-time version numbers; container-only POSIX tooling and
  multi-arch images; password sign-in with tab-scoped session; accessibility built from
  semantic primitives with per-story checks; tracked security findings register.

## Phase 1: Design

### Service boundaries and request paths

The browser uses the frontend origin, whose reverse proxy sends public traffic to Spring
Cloud Gateway. Gateway explicitly allows `/auth/v1/**` and required public signing-key
paths to Supabase's gateway, `/api/v1/tasks/**` and `/api/v1/users/**` to the task API,
`/api/v1/notifications/**` to notifications, and only the Realtime WebSocket client route
`/realtime/v1/websocket` to Supabase's gateway.
Do not expose a catch-all Supabase proxy or any `/internal/**` route.

The frontend edge is an unprivileged static server container (`infra/edge/`) that serves
the built SPA and proxies only `/api/v1/**`, `/auth/v1/**`, the public signing-key path and
`/realtime/v1/websocket` to the gateway, preserving WebSocket upgrades. It sets a strict
Content-Security-Policy (self-only scripts, `connect-src` limited to the same origin),
`frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer`, and `no-store` for
`index.html`; hashed assets may be cached. It performs no authentication decisions.

Gateway, task API, and notification service validate relevant user credentials. The
gateway is a Spring Security WebFlux resource server for `/api/v1/**` (same issuer,
audience, algorithm and expiry rules as the services) so it can reject invalid tokens early
and key rate limits by verified identity; it never forwards its own identity headers, and
downstream services validate the original bearer token independently. Realtime
validates its own user token and private channel authorization; the gateway must not require
an Authorization header on browser WebSocket upgrades. Permit only the expected public
API key in the SDK connection URL; never put user JWTs or server keys in that URL.
Use SDK access-token updates, redact query strings/frames in logs, and apply origin checks
and connection/rate limits. Never accept user identity
from client-supplied forwarding headers.

The task API uses a restricted task database role and private storage credentials. The
notification service uses its own role and task-owned integration HTTP endpoints for
claiming work, scheduling reminders, checking access, and acknowledging events. It has no
SQL privileges on task tables. Realtime owns no durable application inbox and receives no
task-table SELECT grants or change publications. Spring publishes minimal hints through
Supabase's internal gateway using a server-only publisher key; no public broadcast-send route.
Internal calls require rotated, distinct service credentials with explicit allowed
operations; private networking alone is not authentication.

### Identity integration

Prefer the self-hosted Auth asymmetric ES256 configuration and public JWKS. Configure an
exact issuer, audience, allowed algorithm, key refresh, and expiry validation in each
resource server. Internal JWKS URLs may differ from the public issuer; validate the
configured issuer, never infer trusted key locations from an unverified token.
Do not distribute Supabase's legacy signing secret to application services.

An identity-owned directory projection provides stable UUID, display name, email, active
state, and time zone. Provisioning/deactivation synchronizes this projection transactionally
with the identity-management operation: identity-owned `AFTER INSERT/UPDATE/DELETE`
triggers on `auth.users` (installed by `infra/identity/migrations/`) upsert the projection
in the same transaction as the Auth write. `active` derives only from server-controlled
Auth state (`banned_until`, `deleted_at`, confirmation), never from user metadata.
The task/notification roles receive read-only access to the minimal directory contract;
they never list `auth.users` publicly or mutate user management.
Do not trust client-editable profile metadata as authority for account activation.

Time zone and display name are user-editable profile attributes: the signed-in user sets
them in a frontend settings control through the Supabase Auth user-update API
(`user_metadata.time_zone`, `user_metadata.display_name`). The trigger accepts a time zone
only if it is a valid IANA name known to PostgreSQL (`pg_timezone_names`); otherwise it keeps
the previous value. New accounts default to the configured `DEFAULT_TIME_ZONE`, and the
first sign-in offers the browser-detected zone for confirmation. A user changing their
own zone affects only how their own date-only inputs and filters are interpreted; stored
deadlines remain instants with their recorded zone. Deadline forms also allow an
explicit per-task zone (contract `dueTimeZone`).

`tools/identity` is a Java CLI module packaged as a Compose `tools` image, not a
long-running service, because Supabase Auth already owns identity. It provides:
`reconcile` (compares `auth.users` with the projection, reports drift, and repairs it
idempotently with a summary that excludes emails and tokens) and `demo-users` (synthetic
accounts through server-side Auth administration). It has JUnit unit tests and
PostgreSQL-backed integration tests that cover triggers for create, profile change,
invalid time zone, ban, unban and delete, missed sync detected by `reconcile`, and
idempotent demo provisioning.

Demo provisioning is a separate operator command using server-side Auth administration;
it creates synthetic accounts and a usable directory before smoke tests. All accounts
created through supported user-management paths enter the directory automatically.
Active status must be checked on every protected operation and delivery; a valid JWT
alone does not prove a user is still active. Fail closed if status cannot be established.

### Database bootstrap and migrations

Cluster-level objects are created only by a privileged one-shot `db-bootstrap` Compose job
(`infra/database/bootstrap/`) running as the Supabase database administrator after the
database is healthy. It is idempotent and creates the `identity`, `task` and
`notification` schemas; one owner (migration) role and one non-owner runtime role per
service; default privileges and minimal grants; and the read-only directory contract
grants. It then runs Flyway as the administrator over `infra/identity/migrations/` and
`infra/supabase/migrations/` (history table `identity.bootstrap_schema_history`,
`outOfOrder=false`, merge-time versions), on every `up`, so later files reach existing
volumes. Bootstrap credentials are never given to application containers.

Each service image has a `migrate` mode, run as its own one-shot job (`task-api-migrate`,
`notifications-migrate`) with that service's owner role and its own Flyway history table
in its own schema. Runtime containers start only after their migration job completes,
connect with runtime roles, and cannot run DDL. Version numbers are independent per
service. Within a service, versions are assigned when a migration merges (the next free
integer), not when a task is planned, and Flyway keeps `outOfOrder=false`. CI rejects
duplicate or gapped versions and edits to applied migrations. Tasks name migrations by
purpose. Never reset or rewrite applied migrations on demo volumes.

### Consistency, scheduling and notifications

Use per-table sequence-backed bigint primary keys for application entities and preserve
their types in all foreign/event references. Retrieve keys from inserts; no `max(id)+1`
or startup reseeding. Generated IDs may have gaps and are not commit-order watermarks.
Keep outbox claims based on pending state/leases, never a monotonic-ID cursor that could
skip a late-committing event. Guessed sequential IDs still require current authorization.

Task mutations lock the relevant task, verify current participant/creator permissions and
expected version, then commit business state, history, idempotency response, and outbox
rows in one transaction. Task-owned integration operations claim bounded event batches
using PostgreSQL row locks and persisted leases, not long transactions across HTTP calls.

The notification worker deduplicates events in its own transaction before acknowledging
the source lease. A crash before acknowledgment can redeliver the event but cannot
duplicate its inbox entries. Task-owned scheduling operations generate missed reminders
from current deadlines and persistent delivery identities, keeping task rules in the
task service. Claim processing and final inbox visibility recheck current access.

After inbox insertion, notification-owned durable delivery work calls Supabase's private
Broadcast REST endpoint. Duplicate hints are safe; frontend invalidates, never blindly increments
counts. Notifications exposes its own inbox API and filters it through a bounded task
authorization operation before returning entries or unread counts. If authorization is
unavailable, return a visible service error rather than disclose cached access.

### Files

Uploads stream through the task API into private staging objects, with a 10,000,000-byte
limit and bounded content checks, including archive expansion bounds for DOCX/XLSX.
Do not hold a database transaction while streaming. After validation, finalize metadata,
history, and outbox under a current task authorization check; only finalized objects can
be downloaded. Use an idempotent upload identity so lost-response retries cannot duplicate
files/events. Clean abandoned staged objects using a bounded cleanup job with visible errors.

Downloads stream through the task API after current authorization for each GET/range
request. Return attachment disposition, `nosniff`, and `Cache-Control: no-store`; never
redirect to a reusable storage URL. Previously authorized in-flight downloads may finish.
The PostgreSQL/Storage boundary is a staged workflow, not a claimed distributed transaction.

### Frontend and recovery

Authentication and session: the SPA provides sign-in (email and password through Supabase
JS Auth, which is sufficient for the email-disabled demo), sign-out, and a profile settings
control for display name and time zone. Account creation, password reset and recovery remain
user-management operations outside task management (FR-001). The Supabase client uses
`sessionStorage` with automatic token refresh. Router guards send unauthenticated users to
sign-in and then return them to the validated target URL. On sign-out, account switch,
refresh failure or a 401, the app cancels queries, clears the Query cache, tears down
Realtime, and clears search, cursors and account-specific URL state before rendering
another account (FR-030). Sign-in errors do not reveal whether an account exists.

Router validates and owns URL-backed view, status/priority/assignee/date filters and sort
(FR-030); free-text search stays in memory. Refresh/Back/Forward restore non-text settings.
Reject contradictory parameters visibly; reset cursors when conditions change. URLs never
grant access; account switches clear account-specific URL/search/page state.

TanStack Form owns task-create/edit and deadline-request/decision form state. Validate
required fields, lengths, dates and cross-field constraints on the client for feedback;
Spring validates every submission independently. Map allowlisted problem `fieldErrors`
to fields, remaining errors to a form banner, and keep input on 422/409/503. Dirty forms
retain their original version and user edits during background refresh; a conflict prompts
reload/merge rather than silently replacing the version. Reset only after confirmed success
or explicit discard; warn before navigation that would discard edits. Deadline confirmation
is a deliberate action, not an automatic retry.

Query owns memory-only user-scoped server caches. Propagate its AbortSignal through the
generated fetch client to cancel stale searches/list requests; cancellation is not server
mutation rollback. A pending/error mutation is never displayed as saved/Done. Disable
duplicate submits, retain stable retry keys, and apply authoritative responses after success.
Invalidate affected task/detail/list/history/request keys and own inbox/count, not the
whole cache. No automatic replay of ambiguous mutations without idempotency semantics.
Use cursor-based paginated/infinite queries (`initialPageParam`, `getNextPageParam`),
bounded retained pages, accessible Load more and guarded concurrent fetches; avoid a
full-dataset client sort/filter. Poll active queries every 10 seconds and refetch on focus/
reconnect; consume aborted requests so stale results cannot replace the selected view.
No persisted Query cache, private drafts, service-worker task caching, or offline mutations.
Clear caches and cancel outstanding user queries on sign-out/switch/denied access.

Table manages manual server pagination/filtering/sorting plus FR-029 column preferences:
visibility/order only, per user/list in this browser; Title cannot be hidden, controls work
with keyboard, reset exists. Local storage failure/corrupt preferences produce a notice
and session-only/default behavior, never a silent success. No private task data is stored.

Realtime joins only `user:{auth.uid}` as a private receive-only Broadcast channel. A SELECT
policy on `realtime.messages` binds the topic and active account via a tightly scoped
identity helper; no permissive client INSERT or Presence policies. Configure private-only
tenant operation and deny public joins. The helper is not a task-access bypass.
Realtime caches subscription authorization: do not claim policy changes instantly evict
subscribers. Spring rechecks activity and task access before every publish; payloads contain
only signal ID/time, no task content/ID. Reconnect/fresh-token updates reauthorize and
reload inbox/count over HTTP; teardown on sign-out/switch. Polling survives Realtime outages.
Validate pinned Auth/Realtime ES256/tenant-JWKS configuration, expiry/rotation, policies,
REST publisher credentials and gateway upgrade end-to-end before accepting implementation.

### Accessibility (FR-031, SC-017)

The UI contract is [contracts/ui-accessibility.md](contracts/ui-accessibility.md). Build
on native semantic HTML (`button`, `a`, `table`, `form`, `fieldset`, `dialog`) and a small
set of in-house primitives: dialog with focus trap and return, combobox for user search
following the ARIA APG pattern, a status message region, a form error summary, and a
data table. Do not add custom widgets where native elements suffice. One app-level polite
live region announces outcomes of the user's own actions and newly arrived notification
counts. Background refreshes update content silently and never move focus. Route changes
move focus to the page heading and update the document title. Column reordering uses
explicit Move earlier/Move later buttons, not drag only. Status, priority, overdue and unread
indicators always show text or an accessible name and are not conveyed by color alone.
Design tokens are checked for 4.5:1 text contrast and 3:1 contrast for non-text UI and focus
indicators. Layouts reflow at 320 CSS px and work at 200% zoom.

Each user story's component tests run axe-core (`vitest-axe`) on its rendered states, and
its Playwright suite runs `@axe-core/playwright` on each workflow page and dialog in
Chromium, Firefox and WebKit with keyboard-only paths. Any WCAG 2.1 A/AA violation fails CI.
Playwright WebKit approximates Safari; the manual VoiceOver passes use real Safari. Each
story checkpoint records a short manual keyboard and screen-reader pass (VoiceOver with
Safari or NVDA with Firefox) in `evidence/accessibility.md`. The release review repeats
this pass across all core workflows. Automated results are never presented as proof of
conformance.

### Validation and security gates

Map every FR/SC to tasks and tests in the next phase. Include lifecycle/request matrices,
stale versions, idempotent retries, history, self-assignment, active-user revocation,
copied download URLs, interrupted uploads, real database transactions, worker crashes,
lease expiry, deadline restoration, disabled-email epochs, Realtime reconnect/private policies, and
container recreation. Use controllable clocks for all 100 reminder cases.
JUnit/PostgreSQL cases additionally cover concurrent generated IDs, rollback gaps,
foreign/event references, role/sequence privileges and restart persistence. HTTP/client/
Broadcast tests round-trip IDs above 2^53-1 as strings, reject overflow/malformed IDs,
and verify guessed neighboring IDs cannot bypass authorization.

Default gateway rate limits: 120 API requests/minute per authenticated identity,
30 user searches/minute, and 10 new Realtime connections/minute per client address;
return 429 with Retry-After. Bound query pages to 100 and upload concurrency to two per
identity. Keep configured limits shared/testable; no Redis is needed for single-instance
demo counters. Do not silently drop work on retry exhaustion: retain failed work, expose
safe operational metrics, and provide an explicit replay operation.

CI checks contracts, Java/TypeScript builds, targeted suites (including Gateway JUnit and
automated axe-core accessibility checks), secrets, source analysis, Maven/npm dependencies,
Compose config, and all pinned runtime images. Before release,
run composed smoke, restart, scanner, and load scenarios. Record failed/unavailable gates.
Scans have not been executed by this planning workflow.
Scanner outputs (SARIF/JSON) land in ignored `reports/security/`. Every finding is
triaged into the tracked `docs/security/findings-register.md`, which records ID, scanner and
version, severity, affected component and image digest or package, evidence, and the
disposition (fixed, false positive, accepted). Accepted High/Critical exceptions also record
rationale, owner, expiry date and mitigation. `scripts/release-gate.sh` fails when a scan is
missing or failed, when the register is stale for the current revision, or when an
unresolved High/Critical finding has no unexpired exception.

Trust boundaries and abuse cases tested: browser to edge/gateway (forged, expired or
wrong-audience JWTs, API keys used as user tokens, spoofed forwarding/internal headers,
rate-limit abuse); gateway to services (bypass attempts on `/internal/**`); service to
service (missing, wrong-scope or replayed internal credentials); service to database (runtime
role DDL or cross-schema access denied); browser to Realtime (other-user topics,
client sends, public channels); uploads (format spoofing, oversized or zip-bomb archives,
interrupted streams); downloads (copied links and range requests after revocation,
guessed IDs); identity (deactivated users with valid JWTs, user-editable metadata trying
to change activation).
Complete and record manual keyboard and screen-reader review of the core create/assign,
task-update, comment/attachment, list-navigation, and notification workflows before release;
track accessibility defects and resolve them before release.
Add frontend cases for field/form error mapping, dirty conflict preservation, AbortSignal,
late responses after account switching, pending/failed completion, targeted invalidation,
cursor resets, Back/Forward, copied URLs, column preferences and no private browser persistence.
Realtime tests deny wrong topics/anonymous/client sends/public channels, verify cached
authorization limitations and suppress publishes to inactive recipients; JWT expiry/rotation
and missed-event recovery must work through the actual gateway.

## Complexity Tracking

No constitution violations are identified by this design; the explicit automated and manual
accessibility gates and Gateway JUnit coverage above are implementation requirements, not
completed validation evidence. Spring gateway remains user-selected; Supabase Realtime replaces
custom Node delivery and contains no task rules. Required receive-only RLS/tenant setup is
an explicit cost of that simplification. PostgreSQL replaces an additional broker only at the demo's agreed scale.
Production scaling, remote TLS termination, and real-email provider selection remain
separate scope decisions rather than hidden promises.
