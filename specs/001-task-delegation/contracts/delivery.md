# Delivery Contracts v1

## Durable task events

The task service commits the task change, activity event and outbox envelope together.
Envelope: `eventId` (task-outbox sequence ID as decimal string), `schemaVersion=1`,
`eventType`, `taskId` (decimal-string task ID), `actorId` (Supabase user UUID),
`occurredAt`, recipient candidate UUIDs, optional `{reminderType,dueAt}`.
No titles, comments, filenames, descriptions, reasons, tokens, or arbitrary rendered HTML.

Event types cover assignment/reassignment, comment/file addition, deadline edit/removal,
status/block/cancel/reopen, request submission/decision/withdrawal/supersession, and
due-soon/overdue. History-only changes need not generate a user notification unless
required by the spec. Exclude actors; deduplicate self-assigned reminder recipients.

Consumer transaction: deduplicate source event, insert unique inbox entries, create
delivery work, commit; acknowledge source afterward. A repeated event ID must not create
duplicate effects. Invalid envelope/version is a visible failed event, not silently acked.
Store the delivered/suppressed recipient outcome with the consumed event and replay that
same outcome on redelivery, so a lost acknowledgment cannot misclassify delivered work.
Suppression is not delivery: a reminder that becomes applicable again may be requeued
under its existing task/reminder identity with a new event ID only if it never produced
an inbox entry. Delivered reminder identities remain permanently deduplicated.
Leases expire after 60 seconds; renew or finish before expiry. Claims use unique tokens,
not workerId alone. Retry with bounded exponential delay up to 60 seconds, retain failures
after 10 failed attempts, and expose an explicit safe replay path.
Under normal demo load, worker poll interval <=1 second and batch <=100.

Task reminder evaluation runs every <=30 seconds through task-owned operations.
After downtime, current task state decides applicability: expired due-soon work is
suppressed in favor of overdue; changed/no deadline, Done, Cancelled, inactive recipients
or revoked access suppress obsolete reminders. Persistent reminder identities prevent
duplicates when dates are restored. Failures are retained and reported.

## Supabase Realtime private Broadcast

This replaces the Node Socket.IO service/client/room/signal endpoint entirely. Use
supabase-js Realtime subscriptions, not Socket.IO protocol clients. Browser route:
`/realtime/v1/websocket` through Spring gateway to the pinned Supabase gateway, which
rewrites to Realtime's `/socket/websocket`. Keep the matching upstream heartbeat/upgrade
configuration; do not rewrite by guessing. Server-only batch REST publishing goes to
Supabase's internal gateway at `/realtime/v1/api/broadcast`, not through a public send route.
No browser task-table Postgres Changes subscriptions or direct inbox row access.

Logical topic is `user:{Supabase Auth UUID}`, with `{config:{private:true}}`;
do not manually prepend the protocol's `realtime:` prefix. On `realtime.messages`,
authenticated SELECT authorization must require `extension='broadcast'`, exact own-user
topic and active account via a fixed-subject identity-owned helper. No browser INSERT/
Presence policies or broad permissive policies. Set and test tenant `private_only=true`
through the pinned supported internal provisioning mechanism; its default is not sufficient.

The identity helper returns only whether the verified current subject is active, resides
outside exposed schemas, uses controlled ownership, explicit search_path and minimal EXECUTE
grants. It cannot accept an arbitrary user ID or expose directory/task data. Policy failure
denies subscription. Do not treat RLS as a per-message immediate revocation hook: Realtime
caches permissions for an existing channel and rechecks on join/new JWT updates.

Supply current user access token through SDK authentication, update on token refresh,
and tear down channels on sign-out/account switch. The SDK's public API key may appear in
its connection URL; user JWTs/server keys must not. Redact query strings and protocol frames
from access/debug logs. Validate issuer/audience/signing algorithm/expiry using the selected
Auth/Realtime baseline; Realtime tenant JWKS configuration is separate from Spring's JWKS.
No fallback to anonymous/public channels on auth or compatibility failure.

Spring rechecks recipient activity/current task authorization immediately before publishing
and sends only this generic event:

```json
{
  "event": "inbox.changed",
  "payload": {
    "signalId": "9007199254740993",
    "occurredAt": "RFC3339 timestamp"
  }
}
```

The transport emits the event name separately; the example documents name and payload.
`signalId` is the notification service's sequence-generated DeliveryWork ID serialized
as a decimal string, stable across retries. Publisher and browser preserve it as a string,
including above JavaScript's safe-integer limit. `recipientId` remains a Supabase user UUID.
There is deliberately no task ID, title, count increment, or private content.
Client invalidates its own inbox queries. Duplicate/out-of-order hints are harmless.
On every SUBSCRIBED/reconnect or session refresh, reload inbox and unread count through
authorized HTTP; subscribing before initial fetch closes the subscribe/fetch race.
Continue 10-second fallback polling if the connection is unavailable.
No promise of exactly-once push, replay, or proof of browser receipt. An in-flight generic
hint can still arrive after deactivation; task/inbox reads always check current access.

Publisher REST body is `{messages:[{topic,event:"inbox.changed",private:true,payload}]}`,
where payload contains only signalId/occurredAt. `private:true` is mandatory on every send.
Use a server-only service-role JWT or a supported secret API key through Supabase gateway
translation; never send opaque keys directly to Realtime or mix in a browser Authorization
header. These publisher credentials may bypass RLS and must not enter frontend config.
They are a privileged transport capability, not the notification service's SQL role.
Limit key exposure to that adapter, redact it, and include it in secret/rotation checks.

Successful REST acceptance marks delivery work submitted, not client-delivered. The
worker can retry ambiguous/failed Broadcast calls with the same signalId; duplicates
only invalidate queries and cannot duplicate inbox rows. Visible failed delivery retains
its operational error. HTTP acceptance alone does not prove correct channel auth/routing:
end-to-end receipt and unauthorized-client tests are required on pinned images.
Do not use database Broadcast Replay as a delivery guarantee for REST-published hints.

## Email

Eligible: assignments/reassignments, deadline-request submission and approval/decline,
due-soon, overdue. All other updates remain in-app only.
Render a generic prompt plus a frontend link requiring sign-in and current task access.
No task title/body, original filename, comment, blocked reason, or proposed-date reason.

Adapter contract accepts stable idempotency key, recipient address from identity
management, generic template type, and authenticated frontend URL.
Return `accepted(providerReceipt)`, `retryableBeforeAcceptance`, `rejected`, or `unknown`.
Never equate SMTP acceptance with recipient mailbox delivery.
Recheck access, recipient activity, task/reminder applicability and current policy epoch
immediately before submitting; suppress invalid work. A submission already authorized
and in flight when access/policy changes cannot be remotely recalled.

Default disabled: no sends, no sendable backlog. Disable increments policy epoch and
suppresses pending work. Re-enable affects only events created in the new enabled epoch;
old delayed task events remain suppressed even if processed later.
An operator change records who/when; ordinary users cannot toggle global email.

Optional local SMTP capture accepts synthetic addresses only, no external relay.
`unknown` acceptance requires investigation and is not automatically replayed: SMTP cannot
deduplicate an ambiguous accepted submission. Test idempotent adapter semantics using a
fake receiver; do not claim capture SMTP demonstrates exactly-once external sending.
Real email must remain disabled until an adapter with a documented idempotency window
covering retries/replays passes crash/failure scenarios. This is a gate, not a silent fallback.

## Contract/security validation matrix

- Forged/expired/wrong-issuer/wrong-audience token, inactive user, invalid internal scope:
  reject without joining private channels or exposing inbox/task data.
- Worker crash before/after inbox commit and before/after ack: inbox exactly once.
- Realtime outage, missed hints, token refresh, gateway reconnect: HTTP reconciliation works.
- Other-user topics, public joins and browser Broadcast/Presence sends are denied; cached
  subscriptions do not bypass Spring's current-access checks or receive private task text.
- Reassignment after event creation: no private data/attachment/inbox access for old assignee;
  new assignee gets historical task access but not old recipient inbox entries.
- Email disabled during backlog or restored after downtime: no suppressed-period sends.
- Invalid event schema, exhausted retries, ambiguous email: explicit operational failure.
- Source leases, service restarts and repeated delivery: no lost durable inbox state.
