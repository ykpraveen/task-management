# Research: Private Task Delegation

**Date**: 2026-10-27
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

- **Decision**: Java 21 LTS, Spring Boot task API and notification service/worker,
  Spring Cloud Gateway Server WebFlux. Use MVC/JDBC in the business services.
- **Rationale**: User preference; gateway handles routing/security transport, not task rules.
  Blocking PostgreSQL transactions stay out of the reactive gateway.
- **Alternatives considered**: Fastify/TypeScript, FastAPI, .NET; no gateway.
- **Compatibility baseline**: Boot 4.1.2 supports Java 21. Cloud's documented 2025.1
  train supports Boot 4.1 from 2025.1.2; use the compatible train's service-release BOM,
  not independently chosen Spring component versions. Research returned 2025.1.4 as
  a candidate; verify the exact available patch at dependency scaffolding.
- **Sources**: https://docs.spring.io/spring-boot/system-requirements.html ;
  https://spring.io/projects/spring-cloud ;
  https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/global-filters.html
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

## Remaining implementation verification, not unresolved product choices

Resolve exact npm versions, image digests, and upstream Supabase commit together; record
them in manifests/locks and validate Auth/JWKS/Storage/Realtime compatibility. Verify Java dependency
availability, actual gateway upgrade/Broadcast authorization behavior, whole-stack resource usage, scanner
availability, and recovery timing. These are implementation gates, not claimed results.
