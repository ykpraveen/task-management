/*
 * Assumed contract for TaskForm (T047). Rendered inside QueryClientProvider and
 * LiveRegionProvider but outside the router; navigation after a save is the caller's job.
 *
 *   <TaskForm api={ApiClients} session={SessionController} task?={Task} onSaved?={(task: Task) => void} />
 *
 * - Without `task` it creates: submit "Create task", POST /tasks, announces "Task created".
 *   With `task` it edits that task: fields prefilled, submit "Save changes",
 *   PATCH /tasks/{id} with If-Match of `task.version`, announces "Task saved".
 *   `onSaved` receives the server's Task only after a 2xx response.
 * - Labels: "Title (required)", "Assignee (required)" (the a11y Combobox searching GET /users),
 *   "Description", "Priority" (native select Low/Normal/High), "Deadline date", "Deadline time",
 *   "Time zone" (native select, defaulting to the signed-in user's zone). Date + time + zone
 *   are sent as a UTC `dueAt` plus `dueTimeZone`; no date sends neither.
 * - Client and server field errors go through ErrorSummary ("There is a problem") linking to
 *   each field. Only the allowlisted paths title, assigneeId, description, priority, dueAt and
 *   dueTimeZone map to fields; any other path, and non-field failures, appear as visible
 *   banner text and never mark a field invalid.
 * - Idempotency-Key: one key per distinct request body. Re-sending an identical body (retry
 *   after a network error or 5xx) reuses the key so a committed create is not duplicated; any
 *   change to the body, including `confirmPastDeadline: true` after the past-deadline Dialog,
 *   takes a new key, because the server answers a reused key with a different body with
 *   409 IDEMPOTENCY_CONFLICT.
 * - PAST_DEADLINE_CONFIRMATION_REQUIRED opens an alertdialog "Deadline is in the past" with
 *   "Keep past deadline" (resend with confirmPastDeadline: true) and "Change deadline" (close,
 *   send nothing). STALE_VERSION shows the focusable notice "This task changed since you
 *   opened it" with a "Reload" button and keeps the form's values and original version.
 * - While a request is pending the submit button is aria-disabled, a second submit sends
 *   nothing, and nothing announces or reports a save.
 */
import type { Task } from '@task-management/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import { NO_MATCHES_MESSAGE, SEARCH_FAILED_MESSAGE, resultCountMessage } from '../../a11y/Combobox';
import { CREATOR } from '../../test/fakeAuth';
import {
  ASSIGNEE_SUMMARY,
  OUTSIDE_LIVE_REGION,
  TASK_ID,
  UUID,
  createTaskTestApp,
  deferred,
  liveRegion,
  makeTask,
  problem,
  taskResponse,
  type RecordedRequest,
} from '../../test/taskApi';
import { TaskForm } from './TaskForm';

async function renderForm(task?: Task) {
  const app = await createTaskTestApp(CREATOR);
  const onSaved = vi.fn();
  const view = app.render(
    <TaskForm api={app.api} session={app.session} onSaved={onSaved} {...(task ? { task } : {})} />,
  );
  return { ...app, ...view, onSaved, user: userEvent.setup() };
}

async function chooseAssignee(user: UserEvent, query = 'Avery A') {
  await user.type(screen.getByRole('combobox', { name: 'Assignee (required)' }), query);
  await screen.findByRole('option', { name: /Avery Assignee/ });
  await user.keyboard('{ArrowDown}{Enter}');
}

async function fillValidTask(user: UserEvent) {
  await user.type(screen.getByLabelText('Title (required)'), 'Prepare quarterly report');
  await chooseAssignee(user);
}

async function fillPastDeadline(user: UserEvent) {
  await user.clear(screen.getByLabelText('Deadline date'));
  await user.clear(screen.getByLabelText('Deadline time'));
  await user.type(screen.getByLabelText('Deadline date'), '2020-01-15');
  await user.type(screen.getByLabelText('Deadline time'), '09:00');
}

function idempotencyKey(request: RecordedRequest | undefined): string | null {
  return request?.headers.get('Idempotency-Key') ?? null;
}

/** The link in the error summary that targets a field, by the field's element. */
function summaryLinkFor(field: HTMLElement): HTMLElement {
  const summary = screen.getByRole('group', { name: 'There is a problem' });
  const link = within(summary)
    .getAllByRole('link')
    .find((a) => a.getAttribute('href') === `#${field.id}`);
  if (!link) throw new Error(`No error summary link for #${field.id}`);
  return link;
}

function expectFieldError(field: HTMLElement, message?: string) {
  expect(field.id).not.toBe('');
  expect(field).toHaveAttribute('aria-invalid', 'true');
  const linkText = summaryLinkFor(field).textContent ?? '';
  expect(linkText).not.toBe('');
  expect(field).toHaveAccessibleDescription(expect.stringContaining(message ?? linkText));
}

describe('TaskForm: create', () => {
  it('shows labeled fields with required fields marked in text and no axe violations', async () => {
    const { container } = await renderForm();

    const form = screen.getByRole('button', { name: 'Create task' }).closest('form');
    expect(form).not.toBeNull();
    for (const label of ['Title (required)', 'Description', 'Deadline date', 'Deadline time']) {
      expect(form).toContainElement(screen.getByLabelText(label));
    }
    expect(screen.getByRole('combobox', { name: 'Assignee (required)' })).toHaveAttribute('aria-required', 'true');
    const priority = screen.getByLabelText('Priority');
    expect(priority.tagName).toBe('SELECT');
    expect(within(priority).getAllByRole('option').map((o) => o.textContent)).toEqual(['Low', 'Normal', 'High']);
    expect(priority).toHaveDisplayValue('Normal');
    expect(screen.getByLabelText('Time zone')).toHaveValue('Europe/Berlin');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('creates a task through the API with an Idempotency-Key and announces it only after success', async () => {
    const { server, onSaved, user } = await renderForm();
    const created = makeTask();
    server.on('POST /tasks', () => taskResponse(created, 201));

    await fillValidTask(user);
    await user.type(screen.getByLabelText('Description'), 'Collect the numbers from finance.');
    await user.selectOptions(screen.getByLabelText('Priority'), 'High');
    await user.type(screen.getByLabelText('Deadline date'), '2026-12-04');
    await user.type(screen.getByLabelText('Deadline time'), '17:30');
    await user.click(screen.getByRole('button', { name: 'Create task' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(created));
    const [request] = server.calls('POST /tasks');
    expect(request?.body).toEqual(
      expect.objectContaining({
        title: 'Prepare quarterly report',
        assigneeId: ASSIGNEE_SUMMARY.id,
        description: 'Collect the numbers from finance.',
        priority: 'high',
        dueTimeZone: 'Europe/Berlin',
      }),
    );
    // 17:30 in Berlin (UTC+1 in December).
    expect(new Date((request?.body as { dueAt: string }).dueAt).toISOString()).toBe('2026-12-04T16:30:00.000Z');
    expect(request?.body).not.toEqual(expect.objectContaining({ confirmPastDeadline: true }));
    expect(idempotencyKey(request)).toMatch(UUID);
    expect(onSaved.mock.calls[0]![0].id).toBe(TASK_ID);
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Task created'));
  });

  it('sends no deadline when the date is empty', async () => {
    const { server, onSaved, user } = await renderForm();
    server.on('POST /tasks', () => taskResponse(makeTask({ dueAt: null, dueTimeZone: null }), 201));

    await fillValidTask(user);
    await user.click(screen.getByRole('button', { name: 'Create task' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const body = server.calls('POST /tasks')[0]?.body as Record<string, unknown>;
    expect(body.dueAt ?? null).toBeNull();
    expect(body.dueTimeZone ?? null).toBeNull();
  });
});

describe('TaskForm: validation', () => {
  it('focuses the error summary, links to each invalid field and sends nothing', async () => {
    const { server, container, user } = await renderForm();

    await user.click(screen.getByRole('button', { name: 'Create task' }));

    expect(screen.getByRole('group', { name: 'There is a problem' })).toHaveFocus();
    const title = screen.getByLabelText('Title (required)');
    const assignee = screen.getByRole('combobox', { name: 'Assignee (required)' });
    expectFieldError(title);
    expectFieldError(assignee);
    expect(screen.getByLabelText('Description')).not.toHaveAttribute('aria-invalid');
    expect(server.mutations()).toHaveLength(0);
    expect(await axe(container)).toHaveNoViolations();

    await user.click(summaryLinkFor(assignee));
    expect(assignee).toHaveFocus();
    await user.click(summaryLinkFor(title));
    expect(title).toHaveFocus();
  });

  it('treats a whitespace-only title as missing', async () => {
    const { server, user } = await renderForm();

    await user.type(screen.getByLabelText('Title (required)'), '   ');
    await chooseAssignee(user);
    await user.click(screen.getByRole('button', { name: 'Create task' }));

    expect(screen.getByRole('group', { name: 'There is a problem' })).toHaveFocus();
    expectFieldError(screen.getByLabelText('Title (required)'));
    expect(screen.getByRole('combobox', { name: 'Assignee (required)' })).not.toHaveAttribute('aria-invalid');
    expect(server.mutations()).toHaveLength(0);
  });

  it('maps known server field errors to fields, unknown ones to a banner, and keeps the values', async () => {
    const { server, container, onSaved, user } = await renderForm();
    server.once('POST /tasks', () =>
      problem(422, 'VALIDATION_FAILED', {
        fieldErrors: [
          { field: 'title', code: 'too_long', message: 'Title must be 200 characters or fewer' },
          { field: 'assigneeId', code: 'inactive', message: 'Choose an active user' },
          { field: 'internal.constraint', code: 'unknown', message: 'Something else needs attention' },
        ],
      }),
    );

    await fillValidTask(user);
    await user.type(screen.getByLabelText('Description'), 'Draft');
    await user.click(screen.getByRole('button', { name: 'Create task' }));

    const summary = await screen.findByRole('group', { name: 'There is a problem' });
    await waitFor(() => expect(summary).toHaveFocus());
    expectFieldError(screen.getByLabelText('Title (required)'), 'Title must be 200 characters or fewer');
    expectFieldError(screen.getByRole('combobox', { name: 'Assignee (required)' }), 'Choose an active user');
    // The unknown path is shown, but never evaluated as a field reference.
    expect(screen.getByText('Something else needs attention', OUTSIDE_LIVE_REGION)).toBeVisible();
    expect(screen.getByText('Something else needs attention', OUTSIDE_LIVE_REGION).closest('a')).toBeNull();
    expect(container.querySelectorAll('[aria-invalid="true"]')).toHaveLength(2);

    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare quarterly report');
    expect(screen.getByRole('combobox', { name: 'Assignee (required)' })).toHaveValue('Avery Assignee');
    expect(screen.getByLabelText('Description')).toHaveValue('Draft');
    expect(onSaved).not.toHaveBeenCalled();
    expect(liveRegion()).not.toHaveTextContent('Task created');
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('TaskForm: assignee combobox', () => {
  it('announces the number of results and selects with the keyboard', async () => {
    const { container, user } = await renderForm();
    const assignee = screen.getByRole('combobox', { name: 'Assignee (required)' });

    await user.type(assignee, 'Avery');
    expect(await screen.findByText(resultCountMessage(2))).toHaveAttribute('role', 'status');
    expect(assignee).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('option')).toHaveLength(2);
    expect(await axe(container)).toHaveNoViolations();

    await user.keyboard('{ArrowDown}{Enter}');
    expect(assignee).toHaveValue('Avery Assignee');
    expect(assignee).toHaveAttribute('aria-expanded', 'false');
    expect(assignee).toHaveFocus();
  });

  it('searches the directory API with the trimmed query', async () => {
    const { server, user } = await renderForm();

    await user.type(screen.getByRole('combobox', { name: 'Assignee (required)' }), '  Avery ');
    await screen.findByText(resultCountMessage(2));
    expect(server.calls('GET /users').at(-1)?.query.get('query')).toBe('Avery');
  });

  it('announces no matches distinctly from a failed search', async () => {
    const { server, container, user } = await renderForm();
    const assignee = screen.getByRole('combobox', { name: 'Assignee (required)' });

    await user.type(assignee, 'zzz');
    expect(await screen.findByText(NO_MATCHES_MESSAGE)).toHaveAttribute('role', 'status');
    expect(assignee).toHaveAttribute('aria-expanded', 'false');
    expect(await axe(container)).toHaveNoViolations();

    server.on('GET /users', () => problem(503, 'DEPENDENCY_UNAVAILABLE'));
    await user.type(assignee, 'q');
    expect(await screen.findByText(SEARCH_FAILED_MESSAGE, {}, { timeout: 3000 })).toHaveAttribute('role', 'status');
    expect(screen.queryByText(NO_MATCHES_MESSAGE)).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('TaskForm: past deadline confirmation', () => {
  async function submitPastDeadline() {
    const rendered = await renderForm();
    const { server, user } = rendered;
    server.once('POST /tasks', () => problem(422, 'PAST_DEADLINE_CONFIRMATION_REQUIRED'));
    await fillValidTask(user);
    await fillPastDeadline(user);
    await user.click(screen.getByRole('button', { name: 'Create task' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Deadline is in the past' });
    return { ...rendered, dialog };
  }

  it('asks in a dialog, and confirming resends the same task with confirmPastDeadline and a new key', async () => {
    const { server, onSaved, user, dialog } = await submitPastDeadline();
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toContainElement(document.activeElement as HTMLElement);
    expect(await axe(document.body)).toHaveNoViolations();
    server.on('POST /tasks', () => taskResponse(makeTask({ dueAt: '2020-01-15T08:00:00Z', isOverdue: true }), 201));

    await user.click(within(dialog).getByRole('button', { name: 'Keep past deadline' }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const [first, second] = server.calls('POST /tasks');
    expect(new Date((first?.body as { dueAt: string }).dueAt).toISOString()).toBe('2020-01-15T08:00:00.000Z');
    expect(second?.body).toEqual({ ...(first?.body as object), confirmPastDeadline: true });
    // A changed body must not reuse the key: the server would reject it as IDEMPOTENCY_CONFLICT.
    expect(idempotencyKey(second)).toMatch(UUID);
    expect(idempotencyKey(second)).not.toBe(idempotencyKey(first));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Task created'));
  });

  it('closes on Escape, returns focus to the submit button and sends nothing more', async () => {
    const { server, user } = await submitPastDeadline();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create task' })).toHaveFocus();
    expect(server.calls('POST /tasks')).toHaveLength(1);
    expect(screen.getByLabelText('Deadline date')).toHaveValue('2020-01-15');
  });

  it('lets the user change the deadline without sending, keeping the other values', async () => {
    const { server, onSaved, user, dialog } = await submitPastDeadline();

    await user.click(within(dialog).getByRole('button', { name: 'Change deadline' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(server.calls('POST /tasks')).toHaveLength(1);
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare quarterly report');
    expect(screen.getByLabelText('Deadline date')).toHaveValue('2020-01-15');
    expect(liveRegion()).not.toHaveTextContent('Task created');
  });
});

describe('TaskForm: pending and failed requests', () => {
  it('never reports a pending create as saved and ignores a second submit', async () => {
    const { server, container, onSaved, user } = await renderForm();
    const response = deferred();
    server.once('POST /tasks', () => response.promise);

    await fillValidTask(user);
    const submit = screen.getByRole('button', { name: 'Create task' });
    await user.click(submit);
    await waitFor(() => expect(server.calls('POST /tasks')).toHaveLength(1));

    expect(submit).toHaveAttribute('aria-disabled', 'true');
    await user.click(submit);
    expect(server.calls('POST /tasks')).toHaveLength(1);
    expect(onSaved).not.toHaveBeenCalled();
    expect(liveRegion()).not.toHaveTextContent(/created|saved/i);
    expect(await axe(container)).toHaveNoViolations();

    response.resolve(problem(503, 'DEPENDENCY_UNAVAILABLE'));

    expect((await screen.findAllByText(/not (saved|created)/i, OUTSIDE_LIVE_REGION))[0]).toBeVisible();
    expect(onSaved).not.toHaveBeenCalled();
    expect(liveRegion()).not.toHaveTextContent('Task created');
    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare quarterly report');
    expect(submit).not.toHaveAttribute('aria-disabled', 'true');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('retries an unchanged body with the same Idempotency-Key and a changed body with a new one', async () => {
    const { server, onSaved, user } = await renderForm();
    server.once('POST /tasks', () => Promise.reject(new TypeError('Failed to fetch')));
    server.once('POST /tasks', () => problem(503, 'DEPENDENCY_UNAVAILABLE'));
    server.once('POST /tasks', () => taskResponse(makeTask({ title: 'Prepare Q4 report' }), 201));

    await fillValidTask(user);
    await user.click(screen.getByRole('button', { name: 'Create task' }));
    expect((await screen.findAllByText(/not (saved|created)/i, OUTSIDE_LIVE_REGION))[0]).toBeVisible();

    await user.click(screen.getByRole('button', { name: 'Create task' }));
    await waitFor(() => expect(server.calls('POST /tasks')).toHaveLength(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create task' })).not.toHaveAttribute('aria-disabled'));

    await user.clear(screen.getByLabelText('Title (required)'));
    await user.type(screen.getByLabelText('Title (required)'), 'Prepare Q4 report');
    await user.click(screen.getByRole('button', { name: 'Create task' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());

    const [first, retry, changed] = server.calls('POST /tasks');
    expect(retry?.body).toEqual(first?.body);
    expect(idempotencyKey(retry)).toBe(idempotencyKey(first));
    expect(changed?.body).toEqual(expect.objectContaining({ title: 'Prepare Q4 report' }));
    expect(idempotencyKey(changed)).toMatch(UUID);
    expect(idempotencyKey(changed)).not.toBe(idempotencyKey(first));
  });
});

describe('TaskForm: edit', () => {
  const task = makeTask();

  it('prefills the task, saves with If-Match and announces the save', async () => {
    const { server, container, onSaved, user } = await renderForm(task);
    const saved = makeTask({ title: 'Prepare Q4 report', version: '8' });
    server.on(`PATCH /tasks/${TASK_ID}`, () => taskResponse(saved));

    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare quarterly report');
    expect(screen.getByRole('combobox', { name: 'Assignee (required)' })).toHaveValue('Avery Assignee');
    expect(screen.getByLabelText('Time zone')).toHaveValue('Europe/Berlin');
    expect(screen.queryByRole('button', { name: 'Create task' })).not.toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();

    await user.clear(screen.getByLabelText('Title (required)'));
    await user.type(screen.getByLabelText('Title (required)'), 'Prepare Q4 report');
    const submit = screen.getByRole('button', { name: 'Save changes' });
    await user.click(submit);

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    const [request] = server.calls(`PATCH /tasks/${TASK_ID}`);
    expect(request?.headers.get('If-Match')).toBe('"7"');
    expect(idempotencyKey(request)).toMatch(UUID);
    expect(request?.body).toEqual(expect.objectContaining({ title: 'Prepare Q4 report' }));
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Task saved'));
    // The user's own outcome does not move focus.
    expect(submit).toHaveFocus();
  });

  it('never shows a pending edit as saved and keeps the edit when it fails', async () => {
    const { server, onSaved, user } = await renderForm(task);
    const response = deferred();
    server.once(`PATCH /tasks/${TASK_ID}`, () => response.promise);

    await user.clear(screen.getByLabelText('Title (required)'));
    await user.type(screen.getByLabelText('Title (required)'), 'Prepare Q4 report');
    const submit = screen.getByRole('button', { name: 'Save changes' });
    await user.click(submit);
    await waitFor(() => expect(server.calls(`PATCH /tasks/${TASK_ID}`)).toHaveLength(1));

    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(liveRegion()).not.toHaveTextContent(/saved/i);
    expect(onSaved).not.toHaveBeenCalled();

    response.resolve(problem(503, 'DEPENDENCY_UNAVAILABLE'));

    expect((await screen.findAllByText(/not saved/i, OUTSIDE_LIVE_REGION))[0]).toBeVisible();
    expect(liveRegion()).not.toHaveTextContent('Task saved');
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare Q4 report');
  });

  it('shows a focusable conflict notice on a stale version and keeps the values and original version', async () => {
    const { server, container, onSaved, user } = await renderForm(task);
    server.on(`PATCH /tasks/${TASK_ID}`, () => problem(409, 'STALE_VERSION', { currentVersion: '8' }));

    await user.clear(screen.getByLabelText('Title (required)'));
    await user.type(screen.getByLabelText('Title (required)'), 'Prepare Q4 report');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));

    const message = await screen.findByText('This task changed since you opened it', OUTSIDE_LIVE_REGION);
    const notice = message.closest<HTMLElement>('[tabindex]');
    expect(notice).not.toBeNull();
    notice!.focus();
    expect(notice).toHaveFocus();
    expect(within(notice!).getByRole('button', { name: 'Reload' })).toBeInTheDocument();
    await waitFor(() => expect(liveRegion()).toHaveTextContent('This task changed since you opened it'));
    expect(screen.getByLabelText('Title (required)')).toHaveValue('Prepare Q4 report');
    expect(onSaved).not.toHaveBeenCalled();
    expect(liveRegion()).not.toHaveTextContent('Task saved');
    expect(await axe(container)).toHaveNoViolations();

    // Saving again without reloading must not silently adopt the server's newer version.
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(server.calls(`PATCH /tasks/${TASK_ID}`)).toHaveLength(2));
    expect(server.calls(`PATCH /tasks/${TASK_ID}`)[1]?.headers.get('If-Match')).toBe('"7"');
  });

  it('asks before keeping a past deadline on edit', async () => {
    const { server, user } = await renderForm(task);
    server.once(`PATCH /tasks/${TASK_ID}`, () => problem(422, 'PAST_DEADLINE_CONFIRMATION_REQUIRED'));
    server.on(`PATCH /tasks/${TASK_ID}`, () => taskResponse(makeTask({ version: '8' })));

    await fillPastDeadline(user);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    const dialog = await screen.findByRole('alertdialog', { name: 'Deadline is in the past' });
    await user.click(within(dialog).getByRole('button', { name: 'Keep past deadline' }));

    await waitFor(() => expect(server.calls(`PATCH /tasks/${TASK_ID}`)).toHaveLength(2));
    const [first, second] = server.calls(`PATCH /tasks/${TASK_ID}`);
    expect(second?.body).toEqual(expect.objectContaining({ confirmPastDeadline: true }));
    expect(second?.headers.get('If-Match')).toBe('"7"');
    expect(idempotencyKey(second)).not.toBe(idempotencyKey(first));
    await waitFor(() => expect(liveRegion()).toHaveTextContent('Task saved'));
  });
});
