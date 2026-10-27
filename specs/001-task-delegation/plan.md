# Implementation Plan: Private Task Delegation

**Branch**: `develop` (existing branch; no feature branch created) | **Date**: 2026-10-27
**Spec**: [spec.md](spec.md)

**Input**: `specs\001-task-delegation\spec.md`, constitution v1.1.0, and collaborative
planning decisions recorded in [research.md](research.md).

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

**Primary Dependencies**: Spring Boot 4.1.2 on Java 25; compile with Java release 25 and run service/test containers on JDK 25. Compatible Spring Cloud 2025.1.x BOM
(research baseline 2025.1.4; verify availability and compatibility when generating builds);
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
and k6 performance scenarios. Semgrep, dependency checks, Gitleaks, and Trivy cover the
constitution's security gates. Tests and scanners run in Compose tool profiles where useful.
Every Java service includes JUnit unit and Spring integration cases, with bug-fix regression
tests and PostgreSQL-backed persistence tests. CI executes applicable suites and fails on
failures; frontend/E2E tests do not replace JUnit coverage (constitution v1.1.0).

**Target Platform**: Local Linux containers through native Docker or Docker in Windows WSL;
desktop Chromium and Firefox, responsive layout and keyboard-operable core workflows.
Production deployment, native mobile, multi-instance live-delivery scaling, and automatic failover are
not delivery targets. Use synthetic demo data only.

**Project Type**: Containerized web application with independently deployable services.

**Performance Goals**: Spec SC-001 through SC-016; 50 active employees, 1,000 accessible
tasks per employee, p95 list/search/detail under two seconds; active refresh within
15 seconds; applicable reminders and recovery catch-up within five minutes.
Use a 10-second active polling interval with focus/reconnect refresh. Normal notification
events target inbox persistence within five seconds at demo load; Broadcast invalidates
the inbox immediately after persistence. Reminder generation runs at least every 30 seconds.

**Constraints**: Local-only host bindings; no application business bypass through Supabase
REST or Storage; immediate authorization on new requests; no secrets in browser bundles,
logs, images, or tracked configuration. All user data persists through ordinary recreation.
Email disabled by default; optional capture-only SMTP profile, not real recipient delivery.

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
| Bounded microservices | Task ownership and notification ownership are distinct; gateway and Supabase Realtime have transport responsibilities. No direct cross-service business-table writes. Contracts define the only integration operations. |
| Reproducible containers | Root Compose includes frontend, Java services, required Supabase topology including Realtime, migrations, and optional tooling/capture profiles. No custom Node delivery service. Pin images; health-check dependencies; preserve volumes; document native/WSL paths. |
| Security/privacy | JWT validation and active-user checks in each boundary, least-privilege SQL roles, task authorization on every content/download request, local-only administration, private storage, abuse-case tests. |
| Vulnerability checks | Source, dependency, secret, and image scans are required. High/Critical block merge/release absent a constitution-compliant exception; scanner failures are incomplete, not passes. |
| Contracts/reliability | Versioned HTTP/event contracts, task/outbox transactions, leased claims, idempotent inbox insertion, push-as-hint semantics, bounded retries and recovery tests. JUnit unit/integration/regression cases in every Java service; real PostgreSQL persistence tests and mandatory CI execution per v1.1.0. |

Supabase is self-hosted here; the constitution's managed-service example is not a hosting
mandate. No governance exception is requested. SMTP capture is a demo integration only:
real email enablement is gated on an idempotency-capable adapter and delivery-failure tests.

The spec checklist's three technology-neutrality failures reflect user-selected technical
constraints, not missing business requirements. Keep those constraints visible; do not
recheck the items merely to make the checklist green.

## Project Structure

### Documentation (this feature)

```text
specs\001-task-delegation\
  spec.md
  plan.md
  research.md
  data-model.md
  quickstart.md
  contracts\
    http-api.md
    delivery.md
  checklists\
    requirements.md
```

`tasks.md` is generated later by `/speckit-tasks`, not by this command.

### Source Code (planned; not generated by this command)

```text
apps\
  web\                         React SPA and frontend container
services\
  gateway\                     Spring Cloud Gateway, no business database
  task-api\                    Spring MVC, task rules, task-owned migrations
  notifications\               Spring MVC inbox API plus background workers
packages\
  api-client\                  TypeScript client generated from implemented OpenAPI
infra\
  supabase\                    Pinned Compose/config, Realtime tenant and receive-only policies
  identity\                    Directory projection, roles, demo provisioning
  containers\                  Service Dockerfiles and gateway/frontend config
tests\
  contract\
  e2e\
  performance\
scripts\                       Setup, health, scan and validation entrypoints
compose.yaml
pom.xml                        Java reactor; each service independently packaged
package.json                   npm workspaces for web/client
```

**Structure Decision**: One repository, distinct deployment units; shared packages contain
wire contracts/client generation, not shared persistence entities or business repositories.
Compose DNS supplies service discovery. Maven/npm tooling is pinned with wrappers/locks.

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

## Phase 1: Design

### Service boundaries and request paths

The browser uses the frontend origin, whose reverse proxy sends public traffic to Spring
Cloud Gateway. Gateway explicitly allows `/auth/v1/**` and required public signing-key
paths to Supabase's gateway, `/api/v1/tasks/**` and `/api/v1/users/**` to the task API,
`/api/v1/notifications/**` to notifications, and only the Realtime WebSocket client route
`/realtime/v1/websocket` to Supabase's gateway.
Do not expose a catch-all Supabase proxy or any `/internal/**` route.

Gateway, task API, and notification service validate relevant user credentials. Realtime
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
with the identity-management operation, with integration tests for missed/failed sync.
The task/notification roles receive read-only access to the minimal directory contract;
they never list `auth.users` publicly or mutate user management.
Do not trust client-editable profile metadata as authority for account activation.

Demo provisioning is a separate operator command using server-side Auth administration;
it creates synthetic accounts and a usable directory before smoke tests. All accounts
created through supported user-management paths enter the directory automatically.
Active status must be checked on every protected operation and delivery; a valid JWT
alone does not prove a user is still active. Fail closed if status cannot be established.

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

CI checks contracts, Java/TypeScript builds, targeted suites, secrets, source analysis,
Maven/npm dependencies, Compose config, and all pinned runtime images. Before release,
run composed smoke, restart, scanner, and load scenarios. Record failed/unavailable gates.
Scans have not been executed by this planning workflow.
Add frontend cases for field/form error mapping, dirty conflict preservation, AbortSignal,
late responses after account switching, pending/failed completion, targeted invalidation,
cursor resets, Back/Forward, copied URLs, column preferences and no private browser persistence.
Realtime tests deny wrong topics/anonymous/client sends/public channels, verify cached
authorization limitations and suppress publishes to inactive recipients; JWT expiry/rotation
and missed-event recovery must work through the actual gateway.

## Complexity Tracking

No constitution violations. Spring gateway remains user-selected; Supabase Realtime replaces
custom Node delivery and contains no task rules. Required receive-only RLS/tenant setup is
an explicit cost of that simplification. PostgreSQL replaces an additional broker only at the demo's agreed scale.
Production scaling, remote TLS termination, and real-email provider selection remain
separate scope decisions rather than hidden promises.
