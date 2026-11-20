/*
 * Assumed contract for TaskDetails (T048) and the DeadlineRequestPanel it renders (T050).
 * Rendered inside QueryClientProvider and LiveRegionProvider but outside the router.
 *
 *   <TaskDetails api={ApiClients} session={SessionController} taskId={string} />
 *
 * - Loads GET /tasks/{taskId} (taskId is a decimal string, never a number) and its
 *   deadline requests. The task title is the page's h1. Facts are a `dl` whose `dt` terms
 *   include "Status" and "Deadline"; the status is text (StatusText), an undated task shows
 *   "No deadline", and an overdue task shows "Overdue" as text.
 * - Status actions are buttons offered from the task's status and `permissions`: "Start
 *   progress", "Stop progress", "Mark blocked", "Mark done", "Reopen", "Cancel task".
 *   Every mutation sends If-Match with the task's current version and a UUID Idempotency-Key.
 * - "Mark blocked" opens Dialog "Mark task blocked"; "Cancel task" opens Dialog "Cancel task".
 *   Each has a labeled required textarea "Reason (required)" (focused on open; empty or
 *   whitespace-only marks it aria-invalid with a described message and sends nothing) and the
 *   buttons "Mark blocked"/"Keep current status" and "Confirm cancellation"/"Keep task".
 *   A failed request keeps the dialog open with the typed reason.
 * - Outcomes are announced politely as "Status changed to <status>". Nothing changes or is
 *   announced until the server answers 2xx; a failure leaves the old status and shows text
 *   containing "not changed".
 * - STALE_VERSION shows the focusable notice "This task changed since you opened it" (also
 *   announced) with "Reload", which refetches the task and clears the notice.
 * - Reopen answered with PAST_DEADLINE_CONFIRMATION_REQUIRED opens the alertdialog
 *   "Deadline is in the past"; "Keep past deadline" resends with confirmPastDeadline: true and
 *   a new Idempotency-Key (the body changed).
 * - The deadline-request panel is a region named by its h2 "Deadline requests". With
 *   `permissions.requestDeadline` it has a form: "Proposed date", "Proposed time", "Time zone",
 *   "Reason (required)", submit "Submit request", errors through its own ErrorSummary. A
 *   pending request shows the text "Pending"; the creator (`decideDeadlineRequest`) gets
 *   "Approve request for <date>" and "Decline request for <date>", the requester gets
 *   "Withdraw request for <date>", where <date> is the proposed deadline formatted for display.
 */
import type { Task } from '@task-management/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import { ASSIGNEE, CREATOR, type FakeAccount } from '../../test/fakeAuth';
import {
  ASSIGNEE_PERMISSIONS,
  OUTSIDE_LIVE_REGION,
  REQUEST_ID,
  TASK_ID,
  UUID,
  createTaskTestApp,
  deferred,
  json,
  liveRegion,
  makeDeadlineRequest,
  makeTask,
  problem,
  taskResponse,
  type DeadlineRequest,
  type FakeTaskApi,
  type RecordedRequest,
} from '../../test/taskApi';
import { TaskDetails } from './TaskDetails';

const TASK_PATH = `/tasks/${TASK_ID}`;
const REQUESTS_PATH = `${TASK_PATH}/deadline-requests`;

/** Serves the task and its deadline requests from mutable state, as the server would. */
function serveTask(server: FakeTaskApi, task: Task, requests: DeadlineRequest[] = []) {
  const state = { task, requests };
  server.on(`GET ${TASK_PATH}`, () => taskResponse(state.task));
  server.on(`GET ${REQUESTS_PATH}`, () => json({ items: state.requests, nextCursor: null }));
  return state;
}

async function renderDetails(account: FakeAccount, task: Task, requests: DeadlineRequest[] = []) {
  const app = await createTaskTestApp(account);
  const state = serveTask(app.server, task, requests);
  const view = app.render(<TaskDetails api={app.api} session={app.session} taskId={TASK_ID} />);
  await screen.findByRole('heading', { level: 1, name: task.title });
  return { ...app, ...view, state, user: userEvent.setup() };
}

/** The `dd` value for a `dt` term in the task facts. */
function fact(term: string): HTMLElement {
  const dt = screen.getAllByText(term, { selector: 'dt' })[0];
  const dd = dt?.nextElementSibling;
  if (!(dd instanceof HTMLElement) || dd.tagName !== 'DD') throw new Error(`No value for ${term}`);
  return dd;
}

function idempotencyKey(request: RecordedRequest | undefined): string | null {
  return request?.headers.get('Idempotency-Key') ?? null;
}

function expectMutationHeaders(request: RecordedRequest | undefined, version: string) {
  expect(request?.headers.get('If-Match')).toBe(`"${version}"`);
  expect(idempotencyKey(request)).toMatch(UUID);
}

describe('TaskDetails: facts', () => {
  it('shows status, deadline and overdue state as text with no axe violations', async () => {
    const { container, server } = await renderDetails(
      CREATOR,
      makeTask({ status: 'in_progress', dueAt: '2026-11-01T22:59:00Z', isOverdue: true }),
    );

    expect(fact('Status')).toHaveTextContent('In progress');
    expect(fact('Deadline')).toHaveTextContent('2026');
    expect(screen.getByText('Overdue')).toBeVisible();
    // The ID went over the wire as the exact decimal string.
    expect(server.calls(`GET ${TASK_PATH}`).length).toBeGreaterThan(0);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('shows No deadline for an undated task', async () => {
    const { container } = await renderDetails(CREATOR, makeTask({ dueAt: null, dueTimeZone: null }));

    expect(fact('Deadline')).toHaveTextContent('No deadline');
    expect(screen.queryByText('Overdue')).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('TaskDetails: status buttons', () => {
  it.each([
    ['todo', ['Start progress', 'Mark blocked', 'Mark done', 'Cancel task'], ['Stop progress', 'Reopen']],
    ['in_progress', ['Stop progress', 'Mark blocked', 'Mark done', 'Cancel task'], ['Start progress', 'Reopen']],
    ['done', ['Reopen'], ['Start progress', 'Stop progress', 'Mark blocked', 'Mark done', 'Cancel task']],
  ] as const)('offers the creator the right actions for %s', async (status, shown, hidden) => {
    const permissions = { ...makeTask().permissions, reopen: status === 'done', cancel: status !== 'done' };
    await renderDetails(CREATOR, makeTask({ status, permissions }));

    for (const name of shown) expect(screen.getByRole('button', { name })).toBeInTheDocument();
    for (const name of hidden) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
  });

  it('does not offer the assignee cancellation or field editing', async () => {
    await renderDetails(ASSIGNEE, makeTask({ permissions: ASSIGNEE_PERMISSIONS }));

    expect(screen.getByRole('button', { name: 'Start progress' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel task' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Title (required)')).not.toBeInTheDocument();
  });

  it('changes status only after the server answers, then announces it', async () => {
    const { server, state, container, user } = await renderDetails(CREATOR, makeTask());
    const response = deferred();
    server.once(`POST ${TASK_PATH}/status`, () => response.promise);

    const start = screen.getByRole('button', { name: 'Start progress' });
    await user.click(start);
    await waitFor(() => expect(server.calls(`POST ${TASK_PATH}/status`)).toHaveLength(1));

    // Pending: no optimistic status and no announcement.
    expect(fact('Status')).toHaveTextContent('To do');
    expect(liveRegion()).not.toHaveTextContent(/Status changed/);
    expect(start).toHaveAttribute('aria-disabled', 'true');
    await user.click(start);
    expect(server.calls(`POST ${TASK_PATH}/status`)).toHaveLength(1);
    expect(await axe(container)).toHaveNoViolations();

    state.task = makeTask({ status: 'in_progress', version: '8' });
    response.resolve(taskResponse(state.task));

    await waitFor(() => expect(fact('Status')).toHaveTextContent('In progress'));
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Status changed to In progress'));
    const [request] = server.calls(`POST ${TASK_PATH}/status`);
    expect(request?.body).toEqual({ status: 'in_progress' });
    expectMutationHeaders(request, '7');
  });

  it('leaves the old status shown when the change fails', async () => {
    const { server, user } = await renderDetails(CREATOR, makeTask());
    const response = deferred();
    server.once(`POST ${TASK_PATH}/status`, () => response.promise);

    await user.click(screen.getByRole('button', { name: 'Mark done' }));
    await waitFor(() => expect(server.calls(`POST ${TASK_PATH}/status`)).toHaveLength(1));
    expect(fact('Status')).toHaveTextContent('To do');

    response.resolve(problem(503, 'DEPENDENCY_UNAVAILABLE'));

    expect((await screen.findAllByText(/not changed/i, OUTSIDE_LIVE_REGION))[0]).toBeVisible();
    expect(fact('Status')).toHaveTextContent('To do');
    expect(liveRegion()).not.toHaveTextContent('Status changed to Done');
    expect(screen.getByRole('button', { name: 'Mark done' })).not.toHaveAttribute('aria-disabled', 'true');
  });
});

describe('TaskDetails: conflict notice', () => {
  it('shows a focusable notice on a stale version and reloads the current task', async () => {
    const { server, state, container, user } = await renderDetails(CREATOR, makeTask());
    server.once(`POST ${TASK_PATH}/status`, () => problem(409, 'STALE_VERSION', { currentVersion: '8' }));
    // Someone else changed the task meanwhile.
    state.task = makeTask({ status: 'in_progress', version: '8' });

    await user.click(screen.getByRole('button', { name: 'Start progress' }));

    const message = await screen.findByText('This task changed since you opened it', OUTSIDE_LIVE_REGION);
    const notice = message.closest<HTMLElement>('[tabindex]');
    expect(notice).not.toBeNull();
    notice!.focus();
    expect(notice).toHaveFocus();
    await waitFor(() => expect(liveRegion()).toHaveTextContent('This task changed since you opened it'));
    expect(liveRegion()).not.toHaveTextContent(/Status changed/);
    expect(await axe(container)).toHaveNoViolations();

    await user.click(within(notice!).getByRole('button', { name: 'Reload' }));

    await waitFor(() => expect(fact('Status')).toHaveTextContent('In progress'));
    expect(screen.queryByText('This task changed since you opened it', OUTSIDE_LIVE_REGION)).not.toBeInTheDocument();

    server.on(`POST ${TASK_PATH}/status`, () => taskResponse(makeTask({ status: 'todo', version: '9' })));
    await user.click(screen.getByRole('button', { name: 'Stop progress' }));
    await waitFor(() => expect(server.calls(`POST ${TASK_PATH}/status`)).toHaveLength(2));
    expectMutationHeaders(server.calls(`POST ${TASK_PATH}/status`)[1], '8');
  });
});

describe('TaskDetails: blocked reason dialog', () => {
  async function openBlockedDialog() {
    const rendered = await renderDetails(CREATOR, makeTask());
    const invoker = screen.getByRole('button', { name: 'Mark blocked' });
    await rendered.user.click(invoker);
    const dialog = screen.getByRole('dialog', { name: 'Mark task blocked' });
    return { ...rendered, dialog, invoker };
  }

  it('asks for a required reason in a labeled text area', async () => {
    const { dialog } = await openBlockedDialog();

    const reason = within(dialog).getByLabelText('Reason (required)');
    expect(reason.tagName).toBe('TEXTAREA');
    expect(reason).toBeRequired();
    expect(reason).toHaveFocus();
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(within(dialog).getByRole('button', { name: 'Keep current status' })).toBeInTheDocument();
    expect(await axe(document.body)).toHaveNoViolations();
  });

  it('rejects an empty or blank reason without sending', async () => {
    const { server, dialog, user } = await openBlockedDialog();
    const reason = within(dialog).getByLabelText('Reason (required)');

    await user.click(within(dialog).getByRole('button', { name: 'Mark blocked' }));
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(reason).toHaveAccessibleDescription(expect.stringMatching(/\S/));
    expect(await axe(document.body)).toHaveNoViolations();

    await user.type(reason, '   ');
    await user.click(within(dialog).getByRole('button', { name: 'Mark blocked' }));
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(server.mutations()).toHaveLength(0);
  });

  it('marks the task blocked with the reason after the server answers', async () => {
    const { server, state, dialog, user } = await openBlockedDialog();
    server.on(`POST ${TASK_PATH}/status`, () => {
      state.task = makeTask({ status: 'blocked', blockedReason: 'Waiting for finance', version: '8' });
      return taskResponse(state.task);
    });

    await user.type(within(dialog).getByLabelText('Reason (required)'), 'Waiting for finance');
    await user.click(within(dialog).getByRole('button', { name: 'Mark blocked' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const [request] = server.calls(`POST ${TASK_PATH}/status`);
    expect(request?.body).toEqual({ status: 'blocked', reason: 'Waiting for finance' });
    expectMutationHeaders(request, '7');
    await waitFor(() => expect(fact('Status')).toHaveTextContent('Blocked'));
    expect(screen.getAllByText(/Waiting for finance/, OUTSIDE_LIVE_REGION)[0]).toBeVisible();
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Status changed to Blocked'));
  });

  it('closes on Escape or Keep current status and returns focus to the invoker', async () => {
    const { server, invoker, user } = await openBlockedDialog();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(invoker).toHaveFocus();

    await user.click(invoker);
    await user.click(
      within(screen.getByRole('dialog', { name: 'Mark task blocked' })).getByRole('button', {
        name: 'Keep current status',
      }),
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(invoker).toHaveFocus();
    expect(server.mutations()).toHaveLength(0);
    expect(fact('Status')).toHaveTextContent('To do');
  });
});

describe('TaskDetails: cancel dialog', () => {
  async function openCancelDialog() {
    const rendered = await renderDetails(CREATOR, makeTask());
    await rendered.user.click(screen.getByRole('button', { name: 'Cancel task' }));
    const dialog = screen.getByRole('dialog', { name: 'Cancel task' });
    return { ...rendered, dialog };
  }

  it('asks for a required reason with explicit confirm and keep buttons', async () => {
    const { server, dialog, user } = await openCancelDialog();
    const reason = within(dialog).getByLabelText('Reason (required)');

    expect(reason.tagName).toBe('TEXTAREA');
    expect(reason).toBeRequired();
    expect(reason).toHaveFocus();
    expect(within(dialog).getByRole('button', { name: 'Keep task' })).toBeInTheDocument();
    expect(await axe(document.body)).toHaveNoViolations();

    await user.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }));
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(reason).toHaveAccessibleDescription(expect.stringMatching(/\S/));
    expect(server.mutations()).toHaveLength(0);
    expect(await axe(document.body)).toHaveNoViolations();

    await user.click(within(dialog).getByRole('button', { name: 'Keep task' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel task' })).toHaveFocus();
  });

  it('keeps the dialog, the reason and the old status while pending and after a failure', async () => {
    const { server, dialog, user } = await openCancelDialog();
    const response = deferred();
    server.once(`POST ${TASK_PATH}/cancel`, () => response.promise);
    const reason = within(dialog).getByLabelText('Reason (required)');

    await user.type(reason, 'No longer needed');
    const confirm = within(dialog).getByRole('button', { name: 'Confirm cancellation' });
    await user.click(confirm);
    await waitFor(() => expect(server.calls(`POST ${TASK_PATH}/cancel`)).toHaveLength(1));

    expect(confirm).toHaveAttribute('aria-disabled', 'true');
    expect(fact('Status')).toHaveTextContent('To do');
    expect(liveRegion()).not.toHaveTextContent(/Cancelled/);
    expect(await axe(document.body)).toHaveNoViolations();

    response.resolve(problem(503, 'DEPENDENCY_UNAVAILABLE'));

    expect((await screen.findAllByText(/not changed/i, OUTSIDE_LIVE_REGION))[0]).toBeVisible();
    expect(screen.getByRole('dialog', { name: 'Cancel task' })).toBeInTheDocument();
    expect(reason).toHaveValue('No longer needed');
    expect(fact('Status')).toHaveTextContent('To do');
    expect(liveRegion()).not.toHaveTextContent('Status changed to Cancelled');
  });

  it('cancels with the reason and If-Match', async () => {
    const { server, state, dialog, user } = await openCancelDialog();
    server.on(`POST ${TASK_PATH}/cancel`, () => {
      state.task = makeTask({ status: 'cancelled', cancellationReason: 'No longer needed', version: '8' });
      return taskResponse(state.task);
    });

    await user.type(within(dialog).getByLabelText('Reason (required)'), 'No longer needed');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm cancellation' }));

    await waitFor(() => expect(fact('Status')).toHaveTextContent('Cancelled'));
    const [request] = server.calls(`POST ${TASK_PATH}/cancel`);
    expect(request?.body).toEqual({ reason: 'No longer needed' });
    expectMutationHeaders(request, '7');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Status changed to Cancelled'));
  });
});

describe('TaskDetails: reopen with a past deadline', () => {
  it('asks in a dialog and resends with confirmPastDeadline and a new key', async () => {
    const done = makeTask({
      status: 'done',
      dueAt: '2020-01-15T22:59:00Z',
      completedAt: '2020-01-10T09:00:00Z',
      permissions: { ...makeTask().permissions, reopen: true, cancel: false },
    });
    const { server, state, user } = await renderDetails(CREATOR, done);
    server.once(`POST ${TASK_PATH}/reopen`, () => problem(422, 'PAST_DEADLINE_CONFIRMATION_REQUIRED'));
    server.on(`POST ${TASK_PATH}/reopen`, () => {
      state.task = makeTask({ status: 'todo', dueAt: '2020-01-15T22:59:00Z', isOverdue: true, version: '8' });
      return taskResponse(state.task);
    });

    const reopen = screen.getByRole('button', { name: 'Reopen' });
    await user.click(reopen);
    await screen.findByRole('alertdialog', { name: 'Deadline is in the past' });
    expect(fact('Status')).toHaveTextContent('Done');
    expect(await axe(document.body)).toHaveNoViolations();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(reopen).toHaveFocus();
    expect(server.calls(`POST ${TASK_PATH}/reopen`)).toHaveLength(1);

    server.once(`POST ${TASK_PATH}/reopen`, () => problem(422, 'PAST_DEADLINE_CONFIRMATION_REQUIRED'));
    await user.click(reopen);
    const again = await screen.findByRole('alertdialog', { name: 'Deadline is in the past' });
    await user.click(within(again).getByRole('button', { name: 'Keep past deadline' }));

    await waitFor(() => expect(fact('Status')).toHaveTextContent('To do'));
    const calls = server.calls(`POST ${TASK_PATH}/reopen`);
    const confirmed = calls.at(-1);
    expect(confirmed?.body).toEqual({ confirmPastDeadline: true });
    expectMutationHeaders(confirmed, '7');
    expect(idempotencyKey(confirmed)).not.toBe(idempotencyKey(calls.at(-2)));
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Status changed to To do'));
  });
});

describe('DeadlineRequestPanel (in TaskDetails)', () => {
  const APPROVE = /^Approve request for (?=.*\b11\b)(?=.*\b2026\b)/;
  const DECLINE = /^Decline request for (?=.*\b11\b)(?=.*\b2026\b)/;
  const WITHDRAW = /^Withdraw request for (?=.*\b11\b)(?=.*\b2026\b)/;
  const assigneeTask = () => makeTask({ permissions: ASSIGNEE_PERMISSIONS });

  function panel(): HTMLElement {
    return screen.getByRole('region', { name: 'Deadline requests' });
  }

  it('gives the assignee a labeled request form with no axe violations', async () => {
    const { container } = await renderDetails(ASSIGNEE, assigneeTask());

    expect(within(panel()).getByRole('heading', { level: 2, name: 'Deadline requests' })).toBeInTheDocument();
    for (const label of ['Proposed date', 'Proposed time', 'Time zone', 'Reason (required)']) {
      expect(within(panel()).getByLabelText(label)).toBeInTheDocument();
    }
    expect(within(panel()).getByLabelText('Time zone').tagName).toBe('SELECT');
    expect(within(panel()).getByRole('button', { name: 'Submit request' })).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('reports missing fields through a focused error summary and sends nothing', async () => {
    const { server, container, user } = await renderDetails(ASSIGNEE, assigneeTask());

    await user.click(within(panel()).getByRole('button', { name: 'Submit request' }));

    expect(within(panel()).getByRole('group', { name: 'There is a problem' })).toHaveFocus();
    expect(within(panel()).getByLabelText('Proposed date')).toHaveAttribute('aria-invalid', 'true');
    const reason = within(panel()).getByLabelText('Reason (required)');
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(reason).toHaveAccessibleDescription(expect.stringMatching(/\S/));
    expect(server.mutations()).toHaveLength(0);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('submits with the task version and shows the request as Pending only after success', async () => {
    const { server, state, container, user } = await renderDetails(ASSIGNEE, assigneeTask());
    const response = deferred();
    server.once(`POST ${REQUESTS_PATH}`, () => response.promise);

    await user.type(within(panel()).getByLabelText('Proposed date'), '2026-12-11');
    await user.type(within(panel()).getByLabelText('Proposed time'), '17:00');
    await user.selectOptions(within(panel()).getByLabelText('Time zone'), 'Europe/Berlin');
    await user.type(within(panel()).getByLabelText('Reason (required)'), 'Finance closes the books a week later');
    const submit = within(panel()).getByRole('button', { name: 'Submit request' });
    await user.click(submit);
    await waitFor(() => expect(server.calls(`POST ${REQUESTS_PATH}`)).toHaveLength(1));

    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(within(panel()).queryByText('Pending')).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();

    const created = makeDeadlineRequest({ proposedDueAt: '2026-12-11T16:00:00Z' });
    state.requests = [created];
    response.resolve(json(created, 201));

    expect(await within(panel()).findByText('Pending')).toBeVisible();
    expect(within(panel()).getByRole('button', { name: WITHDRAW })).toBeInTheDocument();
    const [request] = server.calls(`POST ${REQUESTS_PATH}`);
    expectMutationHeaders(request, '7');
    const body = request?.body as { proposedDueAt: string; proposedTimeZone: string; reason: string };
    expect(new Date(body.proposedDueAt).toISOString()).toBe('2026-12-11T16:00:00.000Z');
    expect(body).toEqual(
      expect.objectContaining({ proposedTimeZone: 'Europe/Berlin', reason: 'Finance closes the books a week later' }),
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it('maps a server field error to the proposed date and keeps the form values', async () => {
    const { server, user } = await renderDetails(ASSIGNEE, assigneeTask());
    server.once(`POST ${REQUESTS_PATH}`, () =>
      problem(422, 'VALIDATION_FAILED', {
        fieldErrors: [{ field: 'proposedDueAt', code: 'unchanged', message: 'Propose a different deadline' }],
      }),
    );

    await user.type(within(panel()).getByLabelText('Proposed date'), '2026-12-04');
    await user.type(within(panel()).getByLabelText('Reason (required)'), 'Need more time');
    await user.click(within(panel()).getByRole('button', { name: 'Submit request' }));

    const summary = await within(panel()).findByRole('group', { name: 'There is a problem' });
    await waitFor(() => expect(summary).toHaveFocus());
    const date = within(panel()).getByLabelText('Proposed date');
    expect(date).toHaveAttribute('aria-invalid', 'true');
    expect(date).toHaveAccessibleDescription(expect.stringContaining('Propose a different deadline'));
    expect(date).toHaveValue('2026-12-04');
    expect(within(panel()).getByLabelText('Reason (required)')).toHaveValue('Need more time');
    expect(within(panel()).queryByText('Pending')).not.toBeInTheDocument();
  });

  it('shows the creator a pending request with Approve and Decline buttons naming it', async () => {
    const { container } = await renderDetails(CREATOR, makeTask(), [makeDeadlineRequest()]);

    expect(await within(panel()).findByText('Pending')).toBeVisible();
    expect(within(panel()).getByRole('button', { name: APPROVE })).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: DECLINE })).toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: WITHDRAW })).not.toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: 'Submit request' })).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('approves with the task version and shows the result only after success', async () => {
    const { server, state, user } = await renderDetails(CREATOR, makeTask(), [makeDeadlineRequest()]);
    const response = deferred();
    server.once(`POST ${REQUESTS_PATH}/${REQUEST_ID}/approve`, () => response.promise);

    const approve = await within(panel()).findByRole('button', { name: APPROVE });
    await user.click(approve);
    await waitFor(() => expect(server.calls(`POST ${REQUESTS_PATH}/${REQUEST_ID}/approve`)).toHaveLength(1));
    expect(within(panel()).getByText('Pending')).toBeVisible();
    expect(within(panel()).queryByText('Approved')).not.toBeInTheDocument();

    const approved = makeDeadlineRequest({ state: 'approved', resolvedAt: '2026-11-20T10:00:00Z', resolvedBy: makeTask().creator });
    state.task = makeTask({ dueAt: approved.proposedDueAt, version: '8' });
    state.requests = [approved];
    response.resolve(json({ request: approved, task: state.task }, 200, { ETag: '"8"' }));

    expect(await within(panel()).findByText('Approved')).toBeVisible();
    expect(within(panel()).queryByText('Pending')).not.toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: APPROVE })).not.toBeInTheDocument();
    const [request] = server.calls(`POST ${REQUESTS_PATH}/${REQUEST_ID}/approve`);
    expectMutationHeaders(request, '7');
    expect(request?.body ?? {}).not.toEqual(expect.objectContaining({ confirmPastDeadline: true }));
  });

  it('asks before approving a past deadline and resends with confirmPastDeadline and a new key', async () => {
    const { server, state, user } = await renderDetails(CREATOR, makeTask(), [makeDeadlineRequest()]);
    const approvePath = `POST ${REQUESTS_PATH}/${REQUEST_ID}/approve`;
    server.once(approvePath, () => problem(422, 'PAST_DEADLINE_CONFIRMATION_REQUIRED'));
    server.on(approvePath, () => {
      const approved = makeDeadlineRequest({ state: 'approved' });
      state.task = makeTask({ version: '8' });
      state.requests = [approved];
      return json({ request: approved, task: state.task }, 200, { ETag: '"8"' });
    });

    await user.click(await within(panel()).findByRole('button', { name: APPROVE }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Deadline is in the past' });
    expect(await axe(document.body)).toHaveNoViolations();
    await user.click(within(dialog).getByRole('button', { name: 'Keep past deadline' }));

    expect(await within(panel()).findByText('Approved')).toBeVisible();
    const [first, second] = server.calls(approvePath);
    expect(second?.body).toEqual({ confirmPastDeadline: true });
    expectMutationHeaders(second, '7');
    expect(idempotencyKey(second)).not.toBe(idempotencyKey(first));
  });

  it('declines with the task version and no body', async () => {
    const { server, state, user } = await renderDetails(CREATOR, makeTask(), [makeDeadlineRequest()]);
    server.on(`POST ${REQUESTS_PATH}/${REQUEST_ID}/decline`, () => {
      const declined = makeDeadlineRequest({ state: 'declined' });
      state.requests = [declined];
      return json({ request: declined, task: state.task }, 200, { ETag: '"7"' });
    });

    await user.click(await within(panel()).findByRole('button', { name: DECLINE }));

    expect(await within(panel()).findByText('Declined')).toBeVisible();
    const [request] = server.calls(`POST ${REQUESTS_PATH}/${REQUEST_ID}/decline`);
    expectMutationHeaders(request, '7');
    expect(request?.body).toBeUndefined();
  });

  it('lets the requester withdraw their pending request', async () => {
    const { server, state, container, user } = await renderDetails(ASSIGNEE, assigneeTask(), [makeDeadlineRequest()]);
    server.on(`POST ${REQUESTS_PATH}/${REQUEST_ID}/withdraw`, () => {
      const withdrawn = makeDeadlineRequest({ state: 'withdrawn' });
      state.requests = [withdrawn];
      return json({ request: withdrawn, task: state.task }, 200, { ETag: '"7"' });
    });

    expect(await within(panel()).findByText('Pending')).toBeVisible();
    expect(within(panel()).queryByRole('button', { name: APPROVE })).not.toBeInTheDocument();
    expect(within(panel()).queryByRole('button', { name: DECLINE })).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();

    await user.click(within(panel()).getByRole('button', { name: WITHDRAW }));

    expect(await within(panel()).findByText('Withdrawn')).toBeVisible();
    const [request] = server.calls(`POST ${REQUESTS_PATH}/${REQUEST_ID}/withdraw`);
    expectMutationHeaders(request, '7');
    expect(request?.body).toBeUndefined();
  });
});
