# Accessibility Evidence: Private Task Delegation

**Requirements**: spec FR-031 and SC-017; constitution VI (WCAG 2.1 AA, EN 301 549);
[contracts/ui-accessibility.md](../contracts/ui-accessibility.md).

This log records the manual keyboard and screen-reader reviews made at each story checkpoint
and before release. Automated axe-core results (`vitest-axe`, `@axe-core/playwright`) are
prerequisites for a review, not proof of conformance. Each section is filled in by the
checkpoint task named in its heading. This file uses headings and tables only, never checkbox
syntax, so it is not treated as a checklist gate.

## How to record a review

| Field | What to record |
|-------|----------------|
| Status | `Not started`, `In progress`, `Passed` or `Passed with registered defects`; a release review with any unresolved defect is `Blocked` |
| Date | ISO date of the review (YYYY-MM-DD) |
| Revision | Git commit reviewed |
| Browsers | Name and exact version of each browser used |
| Assistive technology | Screen reader and exact version (VoiceOver with Safari, or NVDA with Firefox), plus any zoom, reflow or text-spacing settings |
| Workflows covered | Quickstart scenarios and states exercised (including error, empty, loading, conflict, stale-data and access-denied states) |
| Automated results | The axe runs (component and Playwright) and their outcome for this revision |
| Reviewer | Name or role of the person who did the review |

Findings use one row each. **Severity** is `Blocker` (prevents completing a workflow),
`Major` (a WCAG 2.1 A/AA failure with a workaround) or `Minor` (an inconvenience).
**Disposition** is `Fixed in <commit>`, `Deferred to <task>` (never for a Blocker at release)
or `Not a defect` with a reason.

---

## US1 checkpoint: Delegate and complete a task (T052)

**Scope**: Quickstart scenarios 1, 2 and 9. Keyboard-only and screen-reader pass.

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Browsers | |
| Assistive technology | |
| Workflows covered | |
| Automated results | |
| Reviewer | |

### US1 findings

| ID | Workflow and state | Description | WCAG criterion | Severity | Disposition |
|----|--------------------|-------------|----------------|----------|-------------|
| | | | | | |

## US2 checkpoint: Discuss work and share files (T066)

**Scope**: Quickstart scenarios 1 and 4. Keyboard and screen-reader pass.

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Browsers | |
| Assistive technology | |
| Workflows covered | |
| Automated results | |
| Reviewer | |

### US2 findings

| ID | Workflow and state | Description | WCAG criterion | Severity | Disposition |
|----|--------------------|-------------|----------------|----------|-------------|
| | | | | | |

## US3 checkpoint: Find and prioritize personal work (T078)

**Scope**: Quickstart scenarios 6 and 7. Keyboard and screen-reader pass, including 200% zoom
and 320 CSS px reflow.

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Browsers | |
| Assistive technology | |
| Workflows covered | |
| Automated results | |
| Reviewer | |

### US3 findings

| ID | Workflow and state | Description | WCAG criterion | Severity | Disposition |
|----|--------------------|-------------|----------------|----------|-------------|
| | | | | | |

## US4 checkpoint: Stay informed without chasing updates (T101)

**Scope**: Quickstart scenario 3 and the notification inbox. Keyboard and screen-reader pass.

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Browsers | |
| Assistive technology | |
| Workflows covered | |
| Automated results | |
| Reviewer | |

### US4 findings

| ID | Workflow and state | Description | WCAG criterion | Severity | Disposition |
|----|--------------------|-------------|----------------|----------|-------------|
| | | | | | |

## Release review (T110)

**Scope**: The create/assign, task-update, comment/attachment, list-navigation,
customization, notification and sign-in/profile workflows in Chromium, Firefox and Safari,
including 200% zoom and 320 CSS px reflow. Unresolved defects block release (SC-017).

| Field | Value |
|-------|-------|
| Status | Not started |
| Date | |
| Revision | |
| Browsers | |
| Assistive technology | |
| Workflows covered | |
| Automated results | |
| Reviewer | |

### Release findings

| ID | Workflow and state | Description | WCAG criterion | Severity | Disposition |
|----|--------------------|-------------|----------------|----------|-------------|
| | | | | | |
