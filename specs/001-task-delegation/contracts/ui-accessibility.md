# UI Accessibility Contract

**Requirements**: spec FR-031, SC-017; constitution VI (EAA, EN 301 549, WCAG 2.1 AA).
**Scope**: Every screen and dialog of the web application. Each user story's UI tasks and
tests must satisfy the rows that apply to the workflows they deliver.

## Global obligations

- Conformance target is WCAG 2.1 Level A and AA for every page state, including error,
  empty, loading, conflict, stale-data and access-denied states.
- Every action is reachable and operable with a keyboard alone, in a logical order, with a
  visible focus indicator of at least 3:1 contrast. There are no keyboard traps except a
  modal dialog's intentional focus containment, and Escape closes it.
- Text contrast is at least 4.5:1 (3:1 for large text); non-text UI and state indicators are at
  least 3:1. Content reflows at 320 CSS px without two-dimensional scrolling (data tables may scroll
  horizontally within their own region) and remains usable at 200% zoom and with
  text-spacing overrides.
- Status, priority, overdue, blocked, cancelled and unread state are always available as text
  or an accessible name; color and icons are supplementary.
- Each page has one `h1`, landmark regions (`header`, `nav`, `main`), a skip link, a
  descriptive `<title>` that changes with the route, and `lang` set.
- Route changes move focus to the new page's `h1`. Dialogs move focus to their first
  meaningful control or heading and return focus to the invoking control on close.
- Meaningful icons and images have text alternatives; decorative ones are hidden.
- Time limits: session refresh is automatic; no user-facing timeout discards input.

## Announcements

| Event | Behavior |
|-------|----------|
| Outcome of the user's own action (saved, status changed, comment posted, upload done/failed) | Polite live-region message; focus stays where it was unless the action removed the focused element |
| Form validation or server field errors | Error summary receives focus and links to each field; each field has `aria-invalid` and `aria-describedby` pointing at its message |
| Conflict (409) or stale-data notice | Polite announcement plus a persistent, focusable notice with Reload/Review actions |
| Background refresh with changed data | No announcement and no focus change; changed rows are not reordered under the keyboard focus |
| New notifications arriving | The unread count's accessible name updates; one polite announcement per change at most every 10 seconds |
| Access denied after revocation | Content is cleared; focus moves to the access-denied heading |

## Workflow-specific rules

| Workflow | Rules |
|----------|-------|
| Sign-in and profile | Labeled fields, `autocomplete` tokens, errors announced without revealing account existence; time-zone selector is a native `select` or an APG combobox |
| Create/edit task | Fields inside a `form` with visible labels; required fields are marked in text; the assignee search is an APG combobox announcing the result count, "no matches" and failures as distinct messages; the past-deadline confirmation is an explicit dialog, not a silent checkbox |
| Task details and status | Status changes use buttons or a labeled `select`; Blocked and Cancel reason prompts are dialogs with labeled required text areas; history is an ordered list with actor and time as text |
| Deadline requests | Pending request state is exposed as text; Approve, Decline and Withdraw are buttons naming the request |
| Comments | Composer label and character limit are announced; failed submission keeps text and focus in the composer; the thread is an ordered list with author and time |
| Attachments | The upload control is a native file input with a visible label naming the allowed formats and size; progress uses `progress` with an accessible name; rejection reasons are announced and kept visible |
| Task lists | Semantic `table` with `caption`, column headers and `aria-sort` on the sorted column; filters are a labeled `form` with an Apply action; Load more is a button that keeps focus on the first newly loaded row; empty and failed states are distinct text |
| Column preferences | A dialog listing columns with visibility checkboxes and Move earlier/Move later buttons; Title's checkbox is disabled with an explanation; Reset is a button; the storage-unavailable notice is persistent text |
| Notifications | The inbox is a list of links; read/unread is text; Mark read buttons name their notification; the unread badge has an accessible name with the count |

## Verification per story

1. Component tests run axe-core (`vitest-axe`) against each rendered state the story adds.
2. The story's Playwright suite completes the workflow by keyboard only and runs
   `@axe-core/playwright` (WCAG 2.1 A/AA tags) on each page and open dialog in Chromium,
   Firefox and WebKit. Any violation fails CI.
3. At the story checkpoint, a reviewer records a manual keyboard pass and a screen-reader
   pass (VoiceOver with Safari or NVDA with Firefox) in `evidence/accessibility.md`
   with date, tool versions, findings and their disposition.
4. Before release, the manual review is repeated across all core workflows; unresolved
   defects block release (SC-017). Automated results alone are not proof of conformance.
