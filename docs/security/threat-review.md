# Threat Review: Private Task Delegation

**Requirements**: constitution security and vulnerability-check principles; trust boundaries
and abuse cases in `specs/001-task-delegation/plan.md` ("Validation and security gates").

This log records the security review made at each story checkpoint and the consolidated
release review. Every finding also gets a row in [findings-register.md](findings-register.md),
which is the single place its disposition is tracked. This file uses headings and tables
only, never checkbox syntax, so it is not treated as a checklist gate.

## How to record a review

| Field | What to record |
|-------|----------------|
| Status | `Not started`, `In progress`, `Passed` or `Passed with registered findings`; a release review with an unresolved High/Critical finding and no unexpired exception is `Blocked` |
| Date | ISO date of the review (YYYY-MM-DD) |
| Revision | Git commit and image digests reviewed |
| Trust boundaries | The boundaries examined (browser→edge/gateway, gateway→services, service→service, service→database, browser→Realtime, uploads, downloads, identity) |
| Reviewer | Name or role of the person who did the review |

For each abuse case, record the **evidence**: the automated test (file and test name) or the
manual probe (exact request shape, never real secrets or tokens), and the observed result.
A case without evidence is incomplete, not passed.

---

## US1 checkpoint: Delegate and complete a task (T052)

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Trust boundaries | Browser→edge/gateway, gateway→task API, task API→database, identity |
| Reviewer | |

### US1 abuse cases

| Abuse case | Evidence (test or manual probe) | Result | Finding IDs |
|------------|---------------------------------|--------|-------------|
| Forged, expired and wrong-audience JWTs | | | |
| API keys used as user tokens | | | |
| Inactive users with valid JWTs | | | |
| Outsider receives 404, not 403 | | | |
| Guessed neighboring IDs and IDs above 2^53-1 | | | |
| Idempotency-key reuse with a changed body | | | |
| Spoofed forwarding and identity headers | | | |
| Rate limits and Retry-After | | | |
| Sign-in error disclosure (account existence) | | | |

## US2 checkpoint: Discuss work and share files (T066)

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Trust boundaries | Uploads, downloads, task API→private storage |
| Reviewer | |

### US2 abuse cases

| Abuse case | Evidence (test or manual probe) | Result | Finding IDs |
|------------|---------------------------------|--------|-------------|
| Upload format spoofing | | | |
| Oversized files and zip bombs | | | |
| Interrupted upload streams | | | |
| Filename and Content-Disposition injection | | | |
| `nosniff` and `no-store` on downloads | | | |
| Copied links and Range requests after revocation | | | |

## US3 checkpoint: Find and prioritize personal work (T078)

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Trust boundaries | Browser→edge/gateway, browser storage, logging |
| Reviewer | |

### US3 abuse cases

| Abuse case | Evidence (test or manual probe) | Result | Finding IDs |
|------------|---------------------------------|--------|-------------|
| Tampered or replayed cursors | | | |
| Contradictory or injected URL parameters | | | |
| Copied URLs opened by another account | | | |
| Search queries kept out of logs | | | |
| Browser storage holds only column preferences | | | |

## US4 checkpoint: Stay informed without chasing updates (T101)

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Trust boundaries | Gateway→services, service→service, browser→Realtime, email capture |
| Reviewer | |

### US4 abuse cases

| Abuse case | Evidence (test or manual probe) | Result | Finding IDs |
|------------|---------------------------------|--------|-------------|
| Public `/internal/**` bypass attempts | | | |
| Missing, wrong-scope and replayed internal credentials | | | |
| Realtime other-user, anonymous and inactive topics | | | |
| Realtime client sends and public channels | | | |
| Generic Broadcast and email payloads (no private task text) | | | |
| Publisher-key exposure | | | |
| Inbox access after reassignment | | | |

## Release review (T107)

Consolidates the four checkpoint reviews and repeats every trust boundary and abuse case
from plan.md against the release revision.

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Trust boundaries | All |
| Reviewer | |

### Release abuse cases

| Abuse case | Evidence (test or manual probe) | Result | Finding IDs |
|------------|---------------------------------|--------|-------------|
| Forged and expired tokens | | | |
| Header spoofing | | | |
| `/internal/**` bypass | | | |
| Runtime-role DDL and cross-schema access | | | |
| Realtime topic abuse | | | |
| Upload spoofing and zip bombs | | | |
| Copied links after revocation | | | |
| Guessed IDs | | | |
| Deactivated users with valid JWTs | | | |
| Metadata-driven activation | | | |
| Scanner results reconciled with the findings register | | | |
