# HTTP Contracts v1

Protocol paths use URI separators; filesystem paths in project documentation use backslashes.
These contracts define implementation obligations, not currently running endpoints.
Implement OpenAPI from these contracts and generate the TypeScript client during implementation.

## Shared public conventions

All `/api/v1` calls require a Supabase user Bearer JWT. Services validate signature,
configured issuer/audience/algorithm/expiry and authoritative active status. Task content
requires current creator/assignee access. Unknown or inaccessible task IDs both return 404;
an accessible task with a disallowed operation returns 403. No task-existence leak.

JSON uses camelCase, sequence-generated application IDs encoded as canonical positive
decimal strings (1..9223372036854775807), Supabase user UUIDs, UTC RFC3339 timestamps plus
IANA zone fields, lowercase
enum values from the data model. Task DTOs include `id, creator, assignee, title,
description, priority, status, dueAt, dueTimeZone, blockedReason, cancellationReason,
createdAt, updatedAt, completedAt, version, permissions`. Identity DTOs contain stable
ID, display name and email only. No storage keys, service credentials or SQL metadata.

Application IDs include task/comment/attachment/request/history/outbox/notification/
delivery IDs and their references. OpenAPI defines these as decimal-string schemas,
not JSON numeric int64; generated TypeScript clients, Router params, Query cache keys,
Broadcast payloads and cursors preserve them losslessly as strings. Java uses Long.
Versions and policy epochs backed by bigint also serialize as decimal strings; ETags
contain the decimal version. Reject zero, negative, noncanonical, overflow and JSON-number
IDs with 422. User ID fields retain UUID schemas. Small counts/schemaVersion remain numbers.
Sequential IDs do not authorize access: guessed neighboring IDs must receive the same
404 behavior as any inaccessible resource.

Errors use `application/problem+json`: `type,title,status,code,detail,requestId`,
optional `fieldErrors`. Codes include `VALIDATION_FAILED`, `STALE_VERSION`,
`IDEMPOTENCY_CONFLICT`, `PAST_DEADLINE_CONFIRMATION_REQUIRED`, `NOT_FOUND`,
`FORBIDDEN`, `UNAUTHENTICATED`, `DEPENDENCY_UNAVAILABLE`, `RATE_LIMITED`.
Validation=422, stale/key conflict=409, missing precondition=428, unauthenticated=401,
dependency unavailable=503, oversized upload=413, unsupported content=415. Never return
HTML stack traces or success-shaped failures. 429 includes Retry-After.

`fieldErrors` is an allowlisted array of `{field,code,message}` with DTO field paths such
as `title`, `assigneeId`, `dueAt`, `dueTimeZone`, `reason`, or `proposedDueAt`.
TanStack Form maps known paths to field errors and unknown/general errors to a form banner,
never evaluating arbitrary paths from the server. Client checks are advisory; Spring
enforces all validation/authorization. Preserve dirty values and original version on
validation/conflict errors; no optimistic saved/completed state before a successful response.

Task-field/status/request mutations require `If-Match: "<taskVersion>"`; stale updates
return 409 and the current version only after authorization. All mutating POST/PATCH
requests require a UUID `Idempotency-Key`. Retries preserve key/body; key mismatch=409.
Receipts remain valid seven days. Authenticate/authorize before cached-receipt return;
then replay the receipt before rechecking the original stale version.

Lists use `limit` (default 25, max 100) and opaque keyset `cursor`; response
`{items,nextCursor}`. Search/sort parameters are bound to the cursor; changing them
requires a new first page. Stable tie break `(createdAt,id)`.
Reject unknown/conflicting filters rather than silently ignoring them.

Router page URLs store only validated non-text view/filters/sort. Free-text query is sent
only to the authorized API, never persisted in page URLs or browser storage; redact API
query strings in logs. Pagination cursors stay in query memory, not browser navigation URLs.
Changing filter/search/sort resets cursors. A copied URL confers no additional permissions.
Generated read clients consume AbortSignal; aborted reads cannot publish stale results
into another account/view. Cancellation of a request does not undo a committed mutation.
Query caches/drafts are memory-only; no persistence/offline mutation queue. Targeted
invalidation after success updates related lists/details/history/requests/inbox keys.

## Task API: `/api/v1`

| Method/path | Input | Result and permission |
|-------------|-------|-----------------------|
| GET `/users?query=...&limit=...&cursor=...` | Case-insensitive name/email substring; trimmed 1..200 chars | Active directory identities only; empty query=422; no results=[]; directory failure=503 |
| POST `/tasks` | `title,assigneeId,description?,priority?,dueAt?,dueTimeZone?,confirmPastDeadline?` | 201 Task and Location; any active user; dueAt/zone both set or both null |
| GET `/tasks` | `view=assigned|created`, `query?`, `status?`, `priority?`, `assigneeId?`, `dueFrom?`, `dueTo?`, `noDeadline?`, `sort=dueAsc|dueDesc` | Current participant view; filters ANDed; noDeadline mutually exclusive with date range; null dates last |
| GET `/tasks/{id}` | ID | Task and ETag, both participants |
| PATCH `/tasks/{id}` | Any of title/description/priority/assigneeId/dueAt/dueTimeZone; past confirmation | Creator only; null deadline pair removes date; returns new Task/ETag |
| POST `/tasks/{id}/status` | `status=todo|in_progress|blocked|done`, `reason?` | Open task, either participant; blocked reason required; Done/Cancelled require reopen endpoint first |
| POST `/tasks/{id}/reopen` | `confirmPastDeadline?` | Done: either participant; Cancelled: creator; result todo |
| POST `/tasks/{id}/cancel` | Nonempty `reason` | Creator, open only; preserve content and supersede request |
| GET `/tasks/{id}/comments` | Pagination | Chronological author/time/text; all retained comments |
| POST `/tasks/{id}/comments` | `text` | 201 Comment; either participant, including Done/Cancelled |
| GET `/tasks/{id}/history` | Pagination | Immutable actor/time/type/previous/new values; all history |
| GET `/tasks/{id}/attachments` | Pagination | Ready attachments only; id/name/type/size/uploader/time |
| POST `/tasks/{id}/attachments` | Single multipart `file`; stable idempotency key | 201 only after validated finalization; max file bytes 10,000,000; multipart overhead capped separately; no public partial object |
| GET `/tasks/{id}/attachments/{attachmentId}/content` | Optional Range | Recheck access for each request; 200/206 streamed content; no reusable redirect |
| GET `/tasks/{id}/deadline-requests` | Pagination | All retained request states for current participants |
| POST `/tasks/{id}/deadline-requests` | `proposedDueAt,proposedTimeZone,reason` | Distinct current assignee on open task; one pending request; 201 |
| POST `/tasks/{id}/deadline-requests/{requestId}/approve` | `confirmPastDeadline?` | Creator; request still pending, same task/version; apply date |
| POST `/tasks/{id}/deadline-requests/{requestId}/decline` | Empty body | Creator, pending only |
| POST `/tasks/{id}/deadline-requests/{requestId}/withdraw` | Empty body | Original requester while current assignee, pending only |

Comment/upload creation requires current authorization but not If-Match: it cannot
overwrite task fields. Finalization checks access again. Repeating an upload key returns
the existing ready attachment, resumes a recoverable stage, or returns an explicit in-progress/
failed-operation response; never appends another file/event.
Attachment response headers: sanitized Content-Disposition attachment, content type,
Content-Length where available, Cache-Control no-store, X-Content-Type-Options nosniff.

Public DTOs compute `isOverdue` from current clock/open state, not a persisted label.
Date-only UI input is converted using the creator's zone; server validates zone/instant.
Past-date confirmation is explicit and version-bound. No client-supplied actor/time.

## Notifications API: `/api/v1/notifications`

| Method/path | Result |
|-------------|--------|
| GET `/` with `unreadOnly?`, pagination | Recipient's currently accessible entries: id/type/taskId/createdAt/readAt; no task text |
| GET `/unread-count` | `{count}` excluding entries with revoked task access |
| POST `/{id}/read` | Set own entry read once; idempotent replay, no other user's entry |

The notification service owns these endpoints, not the gateway or task API.
Check current task authorization through the task-owned bounded batch operation before
serving entries/counts. Fail closed with 503 if that check is unavailable. Notification
pages/counts must not leak invisible entries through totals or cursor contents.

## Internal task API: `/internal/v1`

Not publicly routed. Distinct service credentials authorize an explicit worker scope.
Authenticate the caller; never trust a public request containing an internal header.

| Method/path | Contract |
|-------------|----------|
| POST `/outbox/claim` | `{workerId,batchSize<=100}` -> leased v1 events with claim tokens, lease expiry; atomically claim via task-owned SQL |
| POST `/outbox/{eventId}/ack` | Matching unexpired claim token and delivered/suppressed result per recipient; idempotent ack; stale token=409 |
| POST `/outbox/{eventId}/retry` | Claim token plus safe failure code; bounded backoff, attempts and failed state owned by task API |
| POST `/reminders/evaluate` | Idempotent bounded clock-based scan; generate applicable reminder events atomically; worker cannot supply arbitrary task state |
| POST `/authorization/check` | Up to 100 `{taskId,userId}` pairs -> allowed/denied only; current activity/participant checks; no task content |
| POST `/outbox/{eventId}/resolve` | Claim token -> currently applicable original recipients/type; suppress obsolete reminders; never redirect an old assignment event to a new assignee |

Original event recipient candidates may only be removed by access changes, not widened.
A separate reassignment event targets the new assignee. Current authorization is rechecked
again before delivery/read; a result is not a permanent capability token.
For large unread counts, the notification service walks bounded batches; never sends an
unbounded authorization payload or relies on old eligibility to hit a latency target.

## Operational surfaces

Private `/actuator/health/liveness` and `/actuator/health/readiness` endpoints report
non-sensitive status; detailed health/metrics require operator credentials.
Task readiness depends on its required data/auth integration, not the optional worker.
Expose queue age, failed/unknown counts, reminder lag and authorization failure metrics
without task text or tokens. Replay and email-policy changes are authenticated operator
operations, not ordinary user APIs; record actor/time and refuse unsafe ambiguous replays.
Use five-second internal HTTP deadlines, bounded connect timeouts, and retry only idempotent
operations. Do not automatically retry unsafe POSTs without their stable keys.
