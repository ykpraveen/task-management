# Research: Private Task Delegation

**Date**: 2026-10-27 | **Revised**: 2026-10-28 (post-analysis design decisions)
**Scope**: Phase 0 design research and collaborative decisions; no runtime validation claimed.

## Accepted decisions

### PostgreSQL sequence-generated application IDs

- **Decision**: Per-table `bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY` for
  application entities/events/delivery work, replacing UUID primary keys. Keep natural/
  composite keys where already defined and retain Supabase-managed user UUIDs.
- **Rationale**: Explicit user preference for PostgreSQL sequences; identity columns
  use owned sequences and PRIMARY KEY enforces uniqueness. JDBC retrieves generated keys.
  Java Long maps to SQL bigint; decimal-string wire IDs avoid JavaScript precision loss.
- **Alternatives considered**: Application UUID primary keys, explicit nextval defaults,
  numeric JSON int64, and replacing Supabase Auth IDs. Identity provides sequence-backed
  generation without changing upstream identity contracts.
- **Operational rules**: Gaps and out-of-order commits are expected; ID is not a commit
  watermark or permission. Preserve `(created_at,id)` tie breaks and leased queue claims.
  Client retry UUIDs and random lease tokens remain tokens rather than row primary keys.
- **Sources**: https://www.postgresql.org/docs/current/ddl-identity-columns.html ;
  https://www.postgresql.org/docs/current/functions-sequence.html
- **Verification**: JUnit with PostgreSQL must cover concurrent generation, rollback gaps,
  generated-key retrieval, foreign-key/event identity preservation and restart persistence.
  Contract/frontend tests must round-trip IDs above 2^53-1 and reject invalid/overflow IDs.

### Frontend

- **Decision**: React + TypeScript + Vite + TanStack Router/Query/Form/Table, not Vue.
- **Rationale**: The user switched to React after verifying Router supports React/Solid,
  while Query supports Vue separately. A private SPA has no SSR/SEO requirement.
- **Alternatives considered**: Vue + Vue Router + Vue Query; TanStack Start.
- **Sources**: https://tanstack.com/router/latest/docs/framework/react/overview ;
  https://tanstack.com/query/latest/docs/framework/vue/overview ;
  https://react.dev/learn/build-a-react-app-from-scratch ;
  https://vite.dev/guide/
- **Integration rule**: Query owns caching; 10-second active polling meets the 15-second
  freshness requirement with immediate focus/reconnect refetch. Broadcast hints invalidate.
  https://tanstack.com/query/v5/docs/framework/react/reference/useQuery
- **Form**: Client validation, dirty edits and explicit server field/form errors for task
  and deadline forms. Spring remains authoritative. Never reset on failed submission or
  overwrite dirty fields/version during refetch. Async validation is debounced where used;
  submission still validates independently. Map Spring errors, do not assume automatic mapping.
- **Router**: FR-030 URL-backed non-text filters/sort with validation, Back/Forward and
  refresh restoration. No free-text search/cursors/tokens in page URLs; copying confers no access.
- **Query**: Consume AbortSignal in fetch clients, targeted user-scoped invalidation,
  cursor-based paginated/infinite reads and bounded pages. Cancellation is not mutation
  rollback. No private cache persistence or optimistic completion/success before confirmation.
- **Table**: FR-029 manual server query operations and keyboard-operable column preferences
  only in per-account/list local storage. No private rows/drafts in browser storage.
- **Sources**: https://tanstack.com/form/latest/docs/framework/react/guides/validation ;
  https://tanstack.com/query/latest/docs/framework/react/guides/query-cancellation ;
  https://tanstack.com/query/latest/docs/framework/react/guides/query-invalidation ;
  https://tanstack.com/query/latest/docs/framework/react/guides/infinite-queries

### Spring business services and gateway

- **Decision**: Java 25 LTS, Spring Boot task API and notification service/worker,
  Spring Cloud Gateway Server WebFlux. Use MVC/JDBC in the business services.
- **Rationale**: User preference; gateway handles routing/security transport, not task rules.
  Blocking PostgreSQL transactions stay out of the reactive gateway. Java 25 is the
  selected language, compilation target, and service/test runtime.
- **Alternatives considered**: Fastify/TypeScript, FastAPI, .NET; no gateway.
- **Compatibility baseline**: Spring Boot 4.1.2 requires at least Java 17 and documents
  compatibility through Java 27, which includes Java 25. Cloud's documented 2025.1
  train supports Boot 4.1 from 2025.1.2; use the compatible train's service-release BOM,
  not independently chosen Spring component versions. Rechecked 2026-10-28 against the
  official compatibility table: 2025.1.x (Oakwood) lists Boot 4.0.x and 4.1.x (from
  2025.1.2), and 2025.1.4 is the current release. Require at least 2025.1.2; verify the
  Java 25 Maven/compiler/container toolchain at dependency scaffolding.
- **Sources**: https://docs.spring.io/spring-boot/system-requirements.html ;
  https://spring.io/projects/spring-cloud ;
  https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html ;
  https://maven.apache.org/guides/mini/guide-using-toolchains.html
- **Caveat**: Proxy Supabase's WebSocket route through the actual Spring and Supabase
  gateways; preserve required query/upgrade/heartbeat behavior and redact credentials.
  Keep the server publishing endpoint internal; it is not a browser write capability.

### Supabase Realtime private Broadcast replaces Socket.IO

- **Decision**: User approved replacing the custom Node Socket.IO service with the
  self-hosted Supabase Realtime component. Keep the durable Spring notification service,
  PostgreSQL inbox/outbox, and HTTP recovery; do not operate two live channels.
- **Rationale**: Reuse the selected Supabase stack and eliminate a custom server/runtime.
  Private channel policies and tenant/JWT configuration are an explicit integration cost.
- **Alternatives considered**: Existing Node Socket.IO, Spring SSE, polling only,
  Postgres Changes and direct database-trigger broadcasting. REST Broadcast from Spring
  carries minimal hints without publishing task rows or granting browser database access.
- **Authorization**: Own-user `user:<Auth UUID>` topic; receive-only SELECT policy on
  `realtime.messages` binds `realtime.topic()`, `extension='broadcast'`, `auth.uid()` and
  the restricted active-account helper. No browser INSERT/Presence policies; reject
  public joins via tenant `private_only`. Audit existing permissive policies too.
- **Limitation**: Subscription permissions are cached; policy/directory changes do not
  instantly evict connected clients. Spring stops new publishes after activity/access
  checks; generic in-flight hints may arrive, but no task data/ID appears in them and HTTP
  always rechecks access. New token/join reauthorization, expiry and channel cleanup are tested.
- **Publishing**: Internal Supabase gateway batch endpoint `/realtime/v1/api/broadcast`,
  each message explicitly `private:true`. Server-only service-role JWT or configured opaque
  secret-key translation; do not send opaque keys directly to Realtime or forward user
  Authorization into the publisher. REST acceptance is not proof of client receipt.
- **Recovery**: Subscribe then load inbox, refetch after every subscription/reconnect/session
  refresh, retain 10-second polling and harmless duplicate hints. No exactly-once live
  delivery promise or REST-message replay assumption.
- **Compatibility gate**: Pin the complete Supabase baseline, validate Realtime tenant
  ES256/JWKS/key rotation separately from Spring, gateway path rewrites, private-only
  provisioning and publisher key support. Fail visibly on incompatibility, no public fallback.
- **Sources**: https://supabase.com/docs/guides/realtime/broadcast ;
  https://supabase.com/docs/guides/realtime/authorization ;
  https://supabase.com/docs/guides/self-hosting/self-hosted-auth-keys ;
  https://supabase.com/docs/guides/self-hosting/self-hosted-envoy ;
  https://github.com/supabase/realtime
- **Verification basis**: Official documentation/source research, not a local integration
  test. Current upstream support does not prove compatibility of an unselected older image.

### Supabase self-hosted with service-owned business logic

- **Decision**: All required Supabase infrastructure runs locally in Compose. Auth owns
  accounts; PostgreSQL hosts separately restricted schemas; Storage holds private files.
  Browser task reads/writes/downloads cannot bypass the task API.
- **Rationale**: User explicitly wants Supabase with Spring, not a replacement of either.
- **Alternatives considered**: Managed Supabase; direct browser data access with policies.
- **Operational rule**: Use an immutable, attributed upstream Compose baseline and its
  matching dependencies/configuration. Do not invent a three-container replacement or
  remove services without checking dependencies and integration tests.
- **Capacity**: Official Supabase guidance lists 4 GB RAM minimum/8 GB+ recommended for
  its own stack; this does not establish capacity for the additional JVM services and frontend.
- **Sources**: https://supabase.com/docs/guides/self-hosting/docker ;
  https://supabase.com/docs/guides/auth/managing-user-data
- **Identity caveat**: `auth.users` is not a public directory. Maintain an identity-owned,
  minimal projection with authoritative activation state and a read-only service contract.
  Deleting/banning an account must not rely solely on token expiry to stop task access.
- **Keys**: Prefer ES256 and public JWKS for self-hosted Auth. Spring must explicitly accept
  ES256, validate issuer/audience/expiry, and reject API keys as user tokens. If the selected
  baseline lacks usable asymmetric support, upgrade it or stop with an explicit compatibility
  failure; do not silently spread the legacy shared signing secret.
- **Sources**: https://supabase.com/docs/guides/self-hosting/self-hosted-auth-keys ;
  https://supabase.com/docs/guides/auth/jwts ;
  https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html ;
  https://docs.spring.io/spring-security/reference/reactive/oauth2/resource-server/jwt.html

### Transactional outbox without another broker

- **Decision**: Task-owned outbox and reminder operations, notification-owned inbox and
  delivery work, accessed through versioned internal HTTP interfaces.
- **Rationale**: User chose PostgreSQL over Redis/RabbitMQ; transactions keep task/history/
  handoff atomic, while explicit contracts preserve separate service data ownership.
- **Alternatives considered**: Shared unrestricted tables; ephemeral messages; adding a broker.
- **Mechanism**: Short `FOR UPDATE SKIP LOCKED` claims with persisted leases and claim
  tokens; unique consumer/inbox/delivery identities; bounded backoff; crash-safe replay.
  Locks alone do not survive the claim transaction committing.
- **Sources**: https://www.postgresql.org/docs/current/tutorial-transactions.html ;
  https://www.postgresql.org/docs/current/sql-select.html ;
  https://www.postgresql.org/docs/current/ddl-priv.html

### Files and revocation

- **Decision**: Authorized API streaming, private staged objects, atomic metadata/history/
  outbox finalization; no reusable client storage URLs.
- **Rationale**: User confirmed full historical content for current participants and denial
  of new downloads by former participants, including copied old links.
- **Alternatives considered**: Direct signed storage URLs with 60-second revocation delay.
- **Limitation**: Already downloaded files cannot be revoked. Requests authorized before
  reassignment may finish. Private storage and database finalization are not one transaction;
  staging, idempotency, and cleanup make failures explicit and recoverable.

### Email and demo scope

- **Decision**: Local demo only. Email disabled by default; optional capture-only SMTP
  container. Real-email adapters must support idempotent submission before enablement.
- **Rationale**: User accepted this scope and the ordinary SMTP crash ambiguity.
- **Alternatives considered**: Real provider now; no adapter/capture capability.
- **Limit**: SMTP acceptance followed by a lost response cannot be made exactly-once by a
  database transaction or Message-ID. Capture adapter marks ambiguous sends for investigation,
  never blindly retries them; it is not proof of exactly-once external delivery. A real
  adapter's idempotency window must cover the configured retry/replay horizon.
- **Source**: https://www.rfc-editor.org/rfc/rfc5321.html#section-6.1
- **Policy**: Suppressed events never become backlog when enabled later. Capture payloads
  use synthetic addresses and generic content only. Durable inbox delivery remains required.

### Identity directory synchronization (added 2026-10-28)

- **Decision**: Identity-owned triggers on `auth.users` maintain the directory projection
  in the Auth write transaction. A Java `tools/identity` CLI module provides `reconcile` and
  `demo-users`; it is a Compose tools image, not a service.
- **Rationale**: Transactional sync without polling Auth or adding a service boundary that
  the constitution would need to justify; Supabase Auth stays the identity service. A
  reconcile command detects and repairs drift, for example after restoring from a backup
  or after a trigger is disabled. Java keeps it inside the mandated JUnit coverage.
- **Alternatives considered**: An Auth webhook to a new identity service (adds a boundary
  and a delivery failure mode); reading `auth.users` directly from the task API (couples
  it to a Supabase-managed schema); periodic polling only (not transactional).
- **Caveat**: Triggers on a Supabase-managed table must be revalidated on every upgrade
  of the pinned Supabase baseline; the reconcile integration test is the guard.

### Employee time zone (added 2026-10-28)

- **Decision**: User-editable `user_metadata.time_zone`, validated against
  `pg_timezone_names` by the sync trigger, with `DEFAULT_TIME_ZONE` for new accounts and a
  browser-detected suggestion at first sign-in. Deadlines may carry an explicit zone.
- **Rationale**: The time zone is a personal preference that only affects the user's own
  input interpretation; unlike activation, trusting the user for it creates no privilege.
- **Alternatives considered**: Browser zone on every request (inconsistent across devices,
  and breaks FR-010's "selected" zone); administrator-set zone (adds user-management scope).

### Database bootstrap and migration versioning (added 2026-10-28)

- **Decision**: A privileged idempotent `db-bootstrap` job creates schemas and roles and runs
  identity and Realtime SQL; per-service `migrate` jobs use owner roles and separate Flyway
  history tables. Versions are assigned at merge time with `outOfOrder=false`, and CI checks
  that they are unique, have no gaps, and that applied files are unchanged.
- **Rationale**: Roles are cluster-level objects that need administrator rights, which
  must not reach service containers. Merge-time numbering removes the conflicts that
  plan-time numbers created across stories developed in parallel (duplicate V001,
  V003 landing after V004).
- **Alternatives considered**: Services creating their own roles (needs superuser);
  `outOfOrder=true` (makes migration order differ between environments); timestamp
  versions (workable, but unnecessary for a single repository with sequential merges).
- **Source**: https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-out-of-order-setting

### Cross-platform tooling (added 2026-10-28)

- **Decision**: Hosts need only Docker and Compose. Setup, demo users, delivery policy,
  migrations, tests and scans run as Compose tools containers that execute POSIX `sh`
  scripts. Commit both `mvnw` and `mvnw.cmd`. Build and pin `linux/amd64` and `linux/arm64`
  images. Document commands for POSIX shells and PowerShell.
- **Rationale**: The constitution requires native Docker and WSL paths; developers also
  use macOS on Apple Silicon. Linux builder stages need the Unix wrapper; PowerShell-only
  host scripts would exclude macOS and Linux.
- **Alternatives considered**: PowerShell 7 on every host (an extra prerequisite);
  separate `.ps1` and `.sh` host scripts (duplicated logic that can drift).

### Browser authentication and session (added 2026-10-28)

- **Decision**: Email/password sign-in through Supabase JS, with the session in
  `sessionStorage`, automatic refresh, router guards, full cache/Realtime/URL-state
  teardown on sign-out, switch or 401, and a strict CSP from the edge container.
- **Rationale**: Email delivery is disabled in the demo, so magic links are not
  viable. Tab-scoped storage limits token lifetime on shared machines while still
  surviving reloads; CSP reduces the impact of XSS on any browser-held token.
- **Alternatives considered**: Default `localStorage` persistence (outlives the tab);
  in-memory only (lost on every reload, which conflicts with URL-state restoration);
  backend-for-frontend cookie sessions (adds a service boundary for a local demo).
- **Sources**: https://supabase.com/docs/reference/javascript/initializing ;
  https://supabase.com/docs/guides/auth/sessions

### Accessibility approach (added 2026-10-28)

- **Decision**: Native semantic HTML plus a small set of in-house APG-pattern primitives,
  one polite live region, silent background refresh, and button-based column reordering.
  Per story: axe-core in Vitest (`vitest-axe`) and Playwright (`@axe-core/playwright`),
  plus a manual keyboard and screen-reader pass at each checkpoint; release review across
  all workflows.
- **Rationale**: FR-031/SC-017 and constitution VI require accessibility during design and
  implementation, not a final pass. Few custom widgets keep the manual review tractable;
  the 10-second polling interval would flood screen readers if refreshes were announced.
- **Alternatives considered**: A third-party component library (adds a dependency surface
  and still needs verification); release-only audits (violates constitution VI).
- **Sources**: https://www.w3.org/TR/WCAG21/ ; https://www.w3.org/WAI/ARIA/apg/ ;
  https://github.com/dequelabs/axe-core-npm ;
  https://www.etsi.org/deliver/etsi_en/301500_301599/301549/

### Security findings register (added 2026-10-28)

- **Decision**: Scanner output goes to ignored `reports/security/`; triaged findings go into
  the tracked `docs/security/findings-register.md`; `scripts/release-gate.sh` enforces
  completeness and the High/Critical rule.
- **Rationale**: Constitution IV requires recorded severity, component, evidence and
  disposition, and treats missing scans as incomplete; CI logs alone are not durable records.
- **Alternatives considered**: GitHub code-scanning alerts only (not available offline, and
  disposition and exception fields are limited).

## Original specification wording (moved 2026-10-28)

The spec was made technology-neutral. These are the original lines, verbatim, so the
clarification and constraint history keeps the user-selected technology names. The
decisions they record are unchanged and are reflected in plan.md.

- Q: How should employees get access to the tool? A: Supabase provides sign-in and separate user management; a logged-in user needs no additional task-management enrollment and can search other users to assign work.
- Q: Should the browser access task data directly through Supabase, or must task operations go through a dedicated backend service? A: A dedicated task service owns all task reads/writes, comments, attachment authorization, and history; a separate notification worker handles delivery. Supabase supplies authentication, PostgreSQL, and private storage; browser sign-in may use Supabase, but task access cannot bypass the task service.
- Q: Where must the first release run, and who should be able to reach it? A: Local development/demo only; the frontend, application microservices, and self-hosted Supabase must run through Docker Compose. Production hosting is outside this release.
- Q: What should happen to task changes and reminders if the notification worker stops and later restarts? A: Keep task operations working; save task changes, history, and a durable notification handoff together. After restart, catch up within five minutes, recheck current access and deadlines, suppress obsolete reminders, and avoid duplicates.
- Q: Should task-list search, filters, and sorting survive refresh and browser Back/Forward through the page URL? A: Store non-text filters and sorting in the URL; keep free-text search in memory and exclude cursors and private task text.
9. **Given** the notification worker is stopped, **When** a participant saves a task change, **Then** the task and history persist with durable pending notification work; after restart, still-applicable notifications appear within five minutes without duplicates or unauthorized content.
- Task-column preferences store only column identifiers/order/visibility, scoped to the signed-in user and list type in this browser; never persist task content or tokens with them. Invalid saved preferences reset with an explanatory notice. If browser storage is unavailable, customization works for the session and a notice explains that it cannot be saved.
- Background refresh MUST NOT overwrite unsaved form or comment text. Revoked access MUST clear cached task content on the next access-denied response; every subsequent server operation checks current access immediately. If refresh fails, show that displayed data may be stale rather than implying it is current.
- Attachment requests authorized before reassignment may finish; every new download request, including a retry or range request, MUST recheck current access. Client/proxy caching MUST NOT serve attachment content to a former participant without this check.
- After notification-worker downtime, a task now past its deadline receives only an applicable overdue reminder, not a stale due-soon reminder. Done, Cancelled, undated, rescheduled, or inaccessible tasks MUST NOT cause obsolete reminder deliveries. Optional email remains subject to enablement and suppression rules during catch-up.
- **FR-027**: Current participants MUST have access to all retained historical task content, including previous descriptions, metadata, comments, history, and attachments predating their assignment, regardless of contributor. This MUST NOT grant access to another person's private notification inbox. Every attachment download MUST pass current task authorization through the task service; reusable direct-storage links MUST NOT bypass revocation. Previously downloaded files and requests authorized before revocation are outside the revocation guarantee.
- **FR-028**: Successful task changes MUST durably retain their associated history and notification handoff as one all-or-nothing operation. Notification-worker downtime MUST NOT prevent task operations when their required storage is available. Within five minutes after worker recovery under the demo load defined in Assumptions, still-applicable pending notifications and missed deadline reminders MUST be processed with current access and task-state checks, obsolete reminders suppressed, and no duplicate deliveries. Pending work and processing errors MUST be visible to operators. If the task/history/handoff cannot be saved together, the action MUST fail explicitly rather than report success.
- **FR-030**: Task-list view, status, priority, assignee, due-date/no-deadline filters, and sorting MUST be encoded as validated URL parameters and restored on refresh and Back/Forward navigation. Free-text search MUST remain in memory and reset on reload; pagination cursors, tokens, and private task text MUST NOT enter the page URL. Invalid or contradictory URL parameters MUST show an actionable validation error rather than run an unintended query. URL values MUST NOT grant access; sign-out/account switch MUST clear search, cursors, and account-specific list parameters before loading the next user's list. Changing query conditions MUST reset pagination.
- **SC-011**: On a documented supported development machine with required configuration, the full local demo starts through the documented Compose command, reaches healthy status, and supports a two-user assignment/comment/file/completion flow. Ordinary container recreation retains accounts, tasks, history, and files.
- **SC-014**: In stop/restart acceptance cases under the demo load defined in Assumptions, successful task mutations retain history and pending notification work across container restarts; within five minutes of worker recovery, all still-applicable notifications are processed with zero lost, duplicate, or unauthorized deliveries and zero obsolete reminders.
- **SC-016**: All list-navigation acceptance cases restore valid non-text filters and sorting after refresh/Back/Forward, exclude free-text search/cursors/tokens from the page URL, reject invalid parameters visibly, and enforce current access when a URL is copied to another account.
- Confirmed during clarification: personal column visibility/order is included and stored per user/list in this browser only. TanStack Table is the selected frontend tool; it does not replace server-side query operations.
- Planning update: TanStack Form provides task/deadline field validation, dirty-state tracking and server-error mapping; Spring remains authoritative. Router preserves validated non-text filters/sort in page URLs without granting access. Query uses cancellation, targeted invalidation and cursor pagination, with no persistent private cache or optimistic saved/completed state before confirmation.
- Proposed defaults, not separately confirmed: a browser-based experience, the stated priorities, attachment limits, and reminder schedule. Collaborative planning confirmed email disabled by default, optional local capture, and an idempotency-capable adapter required before real delivery; see plan.md and research.md.
- User management and authentication are separate from task management. A logged-in user needs no additional task-specific enrollment. User-selected technical constraints are Docker-self-hosted Supabase for authentication, PostgreSQL, private storage and Realtime, and the TanStack ecosystem for the frontend. Collaborative planning selected React/Vite, Spring Boot business services, Spring Cloud Gateway, Supabase Realtime private Broadcast (replacing the previously chosen Node Socket.IO service), and PostgreSQL durable queues; see plan.md for boundaries and research.md for decisions.
- Confirmed technical boundary: a dedicated task service owns task reads/writes, comments, attachment authorization, and history; a separate notification worker handles delivery. The browser may authenticate directly with Supabase but MUST NOT bypass the task service for task content. Planning MUST preserve atomic task/history changes and reliable delivery handoff across this boundary.
- Confirmed deployment scope: local development/demo only, with the frontend, application microservices, and all required self-hosted Supabase dependencies started through Docker Compose. Production deployment, public exposure, high availability, and production recovery commitments are deferred. Persistent local data and health checks remain required; administrative endpoints MUST remain local-only.
- Confirmed freshness contract: active views refresh within 15 seconds and on tab return. Collaborative planning additionally selected Supabase Realtime private Broadcast inbox-change hints with authoritative HTTP reconciliation; task data retains polling and live transport does not replace durable notifications. Realtime caches subscription permissions, so policy changes do not guarantee instant channel eviction; Spring checks activity/access before publishing and hints contain no task identifiers or content. Authorization revocation applies immediately to server data operations; clearing previously displayed client content follows the next refresh or denied action, not an instantaneous remote erasure guarantee.
- Projects, subtasks, shared boards, completion-approval workflows, recurring tasks, dependencies, time tracking, reporting dashboards, native/device push notifications, external collaborators, and multiple company workspaces are outside the first release. Deadline-change approval, browser private Broadcast inbox-change hints, and optional email delivery for selected events are included.
- Planning update: application entity IDs use PostgreSQL bigint sequences, with lossless decimal-string API encoding; Supabase Auth user UUIDs and retry/security tokens retain their existing formats. See data-model.md and contracts for the identifier boundaries.
- Constitution v1.2.0 governs microservices, Docker Compose, the Windows WSL fallback, mandatory security/vulnerability checks, JUnit unit/integration/regression tests for Java services, and accessible user interfaces (EAA, EN 301 549, WCAG 2.1 AA) with automated and manual keyboard/screen-reader verification. Its managed-Supabase example does not require managed hosting; this feature explicitly selects self-hosted Supabase.
- **FR-026**: While connected and active, visible task details, task lists, and notification counts MUST reflect saved changes within 15 seconds. Returning to the tab MUST initiate an immediate refresh. Refreshes MUST preserve unsaved input, display stale-data errors on failure, and clear inaccessible task content when access is denied. Current server authorization MUST NOT depend on cached frontend permissions.
- **SC-013**: All reassignment/download acceptance cases grant new participants access to retained historical content and deny every new file request by former participants, including requests using previously copied links or cached download routes.
- **SC-008**: All enabled/disabled/failing-email acceptance cases preserve task actions and in-app notifications, send no duplicate or unauthorized email, disclose no private task details, and send no disabled-period backlog after re-enabling. With email enabled and its delivery service available, eligible emails MUST be submitted for delivery within five minutes of their event.

## Remaining implementation verification, not unresolved product choices

Resolve exact npm versions, image digests, and upstream Supabase commit together; record
them in manifests/locks and validate Auth/JWKS/Storage/Realtime compatibility. Verify Java dependency
availability, actual gateway upgrade/Broadcast authorization behavior, whole-stack resource usage, scanner
availability, and recovery timing. These are implementation gates, not claimed results.
