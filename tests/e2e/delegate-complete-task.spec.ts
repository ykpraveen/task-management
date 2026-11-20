import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';
import { expectNoAxeViolations } from './support/a11y';
import { DEMO_ACCOUNTS, signedInContext, type DemoRole } from './support/accounts';
import { navigateByKeyboard, replaceFocusedText, tabAndPress, tabTo } from './support/keyboard';

// US1 checkpoint (quickstart scenarios 1, 2 and 9; spec User Story 1, FR-011, FR-024, FR-025):
// delegate a task, find it, edit it, move it through every status, negotiate its deadline and
// keep it private, using only the keyboard and with axe on every page state and open dialog.

const DIALOG_SELECTOR = '[role="dialog"], [role="alertdialog"]';

/** Each run and browser project gets its own titles, so reruns and parallel projects never collide. */
function uniqueTitle(label: string, projectName: string): string {
  return `${label} ${projectName} ${Date.now().toString(36)}`;
}

/** `yyyy-mm-dd`, `days` from today. Offsets are at least two days, so time zones cannot flip the sign. */
function isoDate(days: number): string {
  const date = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}

interface Session {
  context: BrowserContext;
  page: Page;
}

async function openSessions(browser: Browser, roles: DemoRole[]): Promise<Partial<Record<DemoRole, Session>>> {
  const sessions: Partial<Record<DemoRole, Session>> = {};
  for (const role of roles) {
    const session = await signedInContext(browser, role);
    await expect(session.page.getByRole('heading', { level: 1 })).toBeFocused();
    sessions[role] = session;
  }
  return sessions;
}

function heading1(page: Page, name: string): Locator {
  return page.getByRole('heading', { level: 1, name, exact: true });
}

function dialogNamed(page: Page, name: string): Locator {
  return page.getByRole('dialog', { name }).or(page.getByRole('alertdialog', { name }));
}

function liveRegion(page: Page): Locator {
  return page.getByTestId('live-region');
}

function button(page: Page, name: string | RegExp): Locator {
  return page.getByRole('button', { name, exact: typeof name === 'string' });
}

function editTaskControl(page: Page): Locator {
  return page.getByRole('link', { name: 'Edit task' }).or(page.getByRole('button', { name: 'Edit task' }));
}

/** The panel that owns a section heading ("Deadline requests", "History"): the heading's parent. */
function sectionOf(page: Page, headingName: string): Locator {
  return page.getByRole('heading', { name: headingName, exact: true }).locator('xpath=..');
}

function historyList(page: Page): Locator {
  return sectionOf(page, 'History').getByRole('list').first();
}

function taskIdFromUrl(page: Page): string {
  const match = /\/tasks\/(\d+)(?:[?#].*)?$/.exec(page.url());
  if (!match?.[1]) throw new Error(`Not on a task details URL: ${page.url()}`);
  return match[1];
}

/**
 * Picks an assignee in the APG combobox by keyboard: type the query, wait for the option that shows
 * both display name and email, ArrowDown until it is the active option, Enter.
 */
async function chooseAssignee(page: Page, role: DemoRole, query: string): Promise<void> {
  const account = DEMO_ACCOUNTS[role];
  const combobox = page.getByRole('combobox', { name: 'Assignee (required)' });
  await tabTo(page, combobox);
  await replaceFocusedText(page, query);

  const option = page.getByRole('option', { name: new RegExp(`${account.displayName}.*${account.email.replaceAll('.', '\\.')}`) });
  await expect(option).toBeVisible();
  await expect(combobox).toHaveAttribute('aria-expanded', 'true');
  await expectNoAxeViolations(page, { label: `task form – assignee options for "${query}"` });

  for (let presses = 0; presses < 10; presses += 1) {
    await page.keyboard.press('ArrowDown');
    if ((await option.getAttribute('aria-selected')) === 'true') break;
  }
  await expect(option).toHaveAttribute('aria-selected', 'true');
  await page.keyboard.press('Enter');
  await expect(combobox).toHaveValue(account.displayName);
  await expect(combobox).toBeFocused();
}

/** Chooses a native `select` option by typing its label while the select is focused (type-ahead). */
async function chooseByTyping(page: Page, select: Locator, optionLabel: string): Promise<void> {
  await tabTo(page, select);
  await page.keyboard.type(optionLabel);
  await expect(select.locator('option:checked')).toHaveText(optionLabel);
}

/**
 * Sets a date/time field. Focus is reached by Tab; the value is entered with `fill` because the
 * segment order of native date and time inputs differs between engines and locales, which would
 * make raw keystrokes test the browser rather than the application.
 */
async function setDateField(page: Page, field: Locator, value: string): Promise<void> {
  await tabTo(page, field);
  await field.fill(value);
  await expect(field).toBeFocused();
}

async function expectStatus(page: Page, status: string): Promise<void> {
  await expect(page.getByText(status, { exact: true }).first()).toBeVisible();
}

/** Opens a task row from a personal list by keyboard and lands on its details page. */
async function openFromList(page: Page, listName: 'Assigned to me' | 'Created by me', title: string): Promise<void> {
  await navigateByKeyboard(page, listName);
  const table = page.getByRole('table');
  await expect(table.locator('caption')).toBeVisible();
  const link = table.getByRole('link', { name: title, exact: true });
  await expect(link).toBeVisible();
  await expectNoAxeViolations(page, { label: `${listName} list` });
  await tabAndPress(page, link);
  await expect(heading1(page, title)).toBeFocused();
}

async function expectTaskAbsentFromLists(page: Page, title: string): Promise<void> {
  for (const listName of ['Assigned to me', 'Created by me'] as const) {
    await navigateByKeyboard(page, listName);
    await expect(page.getByRole('link', { name: title, exact: true })).toHaveCount(0);
    await expect(page.getByText(title)).toHaveCount(0);
  }
}

/** The access-denied page: no task content, h1 "Task not available", and it passes axe. */
async function expectNotAvailable(page: Page, title: string, label: string): Promise<void> {
  await expect(heading1(page, 'Task not available')).toBeVisible();
  await expect(page.getByText(title)).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'History' })).toHaveCount(0);
  await expectNoAxeViolations(page, { label });
}

/** Creates a task from the New task page by keyboard and returns its ID from the details URL. */
async function createTask(
  page: Page,
  task: { title: string; assignee: DemoRole; assigneeQuery: string; description?: string; priority?: 'Low' | 'Normal' | 'High' },
): Promise<string> {
  await navigateByKeyboard(page, 'New task');
  await tabTo(page, page.getByLabel('Title (required)'));
  await page.keyboard.type(task.title);
  await chooseAssignee(page, task.assignee, task.assigneeQuery);
  if (task.description) {
    await tabTo(page, page.getByLabel('Description'));
    await page.keyboard.type(task.description);
  }
  if (task.priority) {
    await chooseByTyping(page, page.getByLabel('Priority'), task.priority);
  }
  await tabAndPress(page, button(page, 'Create task'));
  await expect(heading1(page, task.title)).toBeFocused();
  return taskIdFromUrl(page);
}

test.describe('US1 – delegate and complete a task', () => {
  test.describe.configure({ timeout: 300_000 });

  test('create, find, edit, progress, negotiate the deadline and reassign – keyboard only, three-account privacy', async ({
    browser,
  }, testInfo) => {
    const title = uniqueTitle('Prepare quarterly supplier review', testInfo.project.name);
    const blockedReason = 'Waiting for the supplier price list';
    const { creator, assignee, replacement, outsider } = await openSessions(browser, [
      'creator',
      'assignee',
      'replacement',
      'outsider',
    ]);
    if (!creator || !assignee || !replacement || !outsider) throw new Error('Missing demo session');
    const c = creator.page;
    const a = assignee.page;
    let taskId = '';

    await test.step('creator sees field errors for an empty form', async () => {
      await navigateByKeyboard(c, 'New task');
      await expectNoAxeViolations(c, { label: 'new task – empty' });
      await expect(c.getByLabel('Priority').locator('option:checked')).toHaveText('Normal');
      await tabAndPress(c, button(c, 'Create task'));
      await expect(c.getByRole('group', { name: 'There is a problem' })).toBeFocused();
      await expect(c.getByLabel('Title (required)')).toHaveAttribute('aria-invalid', 'true');
      await expectNoAxeViolations(c, { label: 'new task – validation errors' });
    });

    await test.step('creator creates an undated task for the assignee found by name', async () => {
      await tabTo(c, c.getByLabel('Title (required)'));
      await c.keyboard.type(title);
      await chooseAssignee(c, 'assignee', 'Avery');
      await tabTo(c, c.getByLabel('Description'));
      await c.keyboard.type('Collect the supplier figures and draft the review.');
      await chooseByTyping(c, c.getByLabel('Priority'), 'High');
      await tabAndPress(c, button(c, 'Create task'));

      await expect(heading1(c, title)).toBeFocused();
      taskId = taskIdFromUrl(c);
      await expectStatus(c, 'To do');
      await expect(c.getByText('No deadline').first()).toBeVisible();
      await expect(c.getByText('High', { exact: true }).first()).toBeVisible();
      await expect(c.getByText(DEMO_ACCOUNTS.assignee.displayName).first()).toBeVisible();
      await expectNoAxeViolations(c, { label: 'task details – creator, new task' });
    });

    await test.step('task appears in Created by me with No deadline', async () => {
      await navigateByKeyboard(c, 'Created by me');
      const row = c.getByRole('row').filter({ has: c.getByRole('link', { name: title, exact: true }) });
      await expect(row).toContainText('No deadline');
      await expect(row).toContainText('To do');
      await expect(row).toContainText(DEMO_ACCOUNTS.assignee.displayName);
      await expect(c.getByRole('table').locator('caption')).toBeVisible();
      await expectNoAxeViolations(c, { label: 'Created by me list' });
      await tabAndPress(c, row.getByRole('link', { name: title, exact: true }));
      await expect(heading1(c, title)).toBeFocused();
    });

    await test.step('assignee finds it in Assigned to me and cannot edit task fields', async () => {
      await openFromList(a, 'Assigned to me', title);
      await expect(a.getByText('Collect the supplier figures and draft the review.')).toBeVisible();
      await expect(editTaskControl(a)).toHaveCount(0);
      await expect(button(a, 'Save changes')).toHaveCount(0);
      await expect(button(a, 'Cancel task')).toHaveCount(0);
      await expect(a.getByLabel('Title (required)')).toHaveCount(0);
      await expectNoAxeViolations(a, { label: 'task details – assignee' });
    });

    await test.step('outsider cannot open the task, a guessed neighbouring ID, or find it in lists', async () => {
      const o = outsider.page;
      await o.goto(`/tasks/${taskId}`);
      await expectNotAvailable(o, title, 'task not available – outsider, task URL');

      const neighbour = (BigInt(taskId) + 1n).toString();
      await o.goto(`/tasks/${neighbour}`);
      await expectNotAvailable(o, title, 'task not available – outsider, guessed ID');
      if (BigInt(taskId) > 1n) {
        await o.goto(`/tasks/${(BigInt(taskId) - 1n).toString()}`);
        await expect(heading1(o, 'Task not available')).toBeVisible();
      }

      await expectTaskAbsentFromLists(o, title);
      await expectNoAxeViolations(o, { label: 'outsider lists' });
    });

    await test.step('creator edits description, priority and a past deadline after confirmation (FR-011)', async () => {
      await tabAndPress(c, editTaskControl(c));
      const saveButton = button(c, 'Save changes');
      await expect(saveButton).toBeVisible();
      await expectNoAxeViolations(c, { label: 'edit task – initial' });

      await tabTo(c, c.getByLabel('Description'));
      await replaceFocusedText(c, 'Collect supplier figures, compare prices and draft the review.');
      await chooseByTyping(c, c.getByLabel('Priority'), 'Low');
      await setDateField(c, c.getByLabel('Deadline date'), isoDate(-3));

      await tabAndPress(c, saveButton);
      const pastDialog = dialogNamed(c, 'Deadline is in the past');
      await expect(pastDialog).toBeVisible();
      await expect(pastDialog).toContainText(/overdue/i);
      await expectNoAxeViolations(c, { include: DIALOG_SELECTOR, label: 'dialog – deadline is in the past (edit)' });

      // Change deadline closes the dialog without saving; nothing is announced as saved.
      await tabAndPress(c, pastDialog.getByRole('button', { name: 'Change deadline' }));
      await expect(pastDialog).toBeHidden();
      await expect(liveRegion(c)).not.toHaveText('Task saved');
      await expect(saveButton).toBeVisible();

      // Escape also closes it and returns focus to Save changes.
      await tabAndPress(c, saveButton);
      await expect(pastDialog).toBeVisible();
      await c.keyboard.press('Escape');
      await expect(pastDialog).toBeHidden();
      await expect(saveButton).toBeFocused();

      await c.keyboard.press('Enter');
      await expect(pastDialog).toBeVisible();
      await tabAndPress(c, pastDialog.getByRole('button', { name: 'Keep past deadline' }));
      await expect(pastDialog).toBeHidden();
      await expect(liveRegion(c)).toHaveText('Task saved');
      await expect(heading1(c, title)).toBeVisible();
      await expect(c.getByText('Collect supplier figures, compare prices and draft the review.')).toBeVisible();
      await expect(c.getByText('Low', { exact: true }).first()).toBeVisible();
      await expect(c.getByText(/overdue/i).first()).toBeVisible();
      await expect(c.getByText('No deadline')).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'task details – creator, overdue' });
    });

    await test.step('assignee sees the creator\'s edits', async () => {
      await a.reload();
      await expect(heading1(a, title)).toBeVisible();
      await expect(a.getByText('Collect supplier figures, compare prices and draft the review.')).toBeVisible();
      await expect(a.getByText(/overdue/i).first()).toBeVisible();
    });

    await test.step('assignee starts, stops and restarts progress', async () => {
      await tabAndPress(a, button(a, 'Start progress'), 'Space');
      await expect(liveRegion(a)).toHaveText('Status changed to In progress');
      await expectStatus(a, 'In progress');
      await expectNoAxeViolations(a, { label: 'task details – in progress' });

      await tabAndPress(a, button(a, 'Stop progress'), 'Space');
      await expect(liveRegion(a)).toHaveText('Status changed to To do');
      await expectStatus(a, 'To do');

      await tabAndPress(a, button(a, 'Start progress'));
      await expect(liveRegion(a)).toHaveText('Status changed to In progress');
    });

    await test.step('assignee marks it blocked with a required reason', async () => {
      const markBlocked = button(a, 'Mark blocked');
      await tabAndPress(a, markBlocked);
      const dialog = dialogNamed(a, 'Mark task blocked');
      await expect(dialog).toBeVisible();
      const reason = dialog.getByLabel('Reason (required)');
      await expect(reason).toBeFocused();
      await expectNoAxeViolations(a, { include: DIALOG_SELECTOR, label: 'dialog – mark task blocked' });

      // Keep current status closes without a change and returns focus to the invoker.
      await tabAndPress(a, dialog.getByRole('button', { name: 'Keep current status' }));
      await expect(dialog).toBeHidden();
      await expect(markBlocked).toBeFocused();
      await expectStatus(a, 'In progress');

      // An empty reason is rejected inside the dialog.
      await a.keyboard.press('Enter');
      await expect(dialog).toBeVisible();
      await expect(reason).toBeFocused();
      await tabAndPress(a, dialog.getByRole('button', { name: 'Mark blocked' }));
      await expect(dialog).toBeVisible();
      await expect(reason).toHaveAttribute('aria-invalid', 'true');
      await expectNoAxeViolations(a, { include: DIALOG_SELECTOR, label: 'dialog – mark task blocked, empty reason' });

      await tabTo(a, reason);
      await a.keyboard.type(blockedReason);
      await tabAndPress(a, dialog.getByRole('button', { name: 'Mark blocked' }));
      await expect(dialog).toBeHidden();
      await expect(liveRegion(a)).toHaveText('Status changed to Blocked');
      await expectStatus(a, 'Blocked');
      await expect(a.getByText(blockedReason).first()).toBeVisible();
      await expectNoAxeViolations(a, { label: 'task details – blocked' });
    });

    await test.step('creator sees the blocked status and its reason', async () => {
      await c.reload();
      await expect(heading1(c, title)).toBeVisible();
      await expectStatus(c, 'Blocked');
      await expect(c.getByText(blockedReason).first()).toBeVisible();
    });

    await test.step('assignee marks it done; it is no longer overdue', async () => {
      await tabAndPress(a, button(a, 'Mark done'));
      await expect(liveRegion(a)).toHaveText('Status changed to Done');
      await expectStatus(a, 'Done');
      await expect(button(a, 'Reopen')).toBeVisible();
      await expect(button(a, 'Mark done')).toHaveCount(0);
      await expect(a.getByText(/^Overdue$/i)).toHaveCount(0);
      // Leaving Blocked clears the current reason; it remains only in history.
      await expect(a.getByText(blockedReason)).toHaveCount(await historyList(a).getByText(blockedReason).count());
      await expectNoAxeViolations(a, { label: 'task details – done' });

      await c.reload();
      await expectStatus(c, 'Done');
    });

    await test.step('assignee reopens Done with a past deadline only after confirmation (FR-011)', async () => {
      await tabAndPress(a, button(a, 'Reopen'));
      const pastDialog = dialogNamed(a, 'Deadline is in the past');
      await expect(pastDialog).toBeVisible();
      await expectNoAxeViolations(a, { include: DIALOG_SELECTOR, label: 'dialog – deadline is in the past (reopen)' });
      await tabAndPress(a, pastDialog.getByRole('button', { name: 'Keep past deadline' }));
      await expect(pastDialog).toBeHidden();
      await expect(liveRegion(a)).toHaveText('Status changed to To do');
      await expectStatus(a, 'To do');
      await expect(a.getByText(/overdue/i).first()).toBeVisible();
      await expectNoAxeViolations(a, { label: 'task details – reopened, overdue' });
    });

    await test.step('creator removes the deadline (FR-025)', async () => {
      await c.reload();
      await expect(heading1(c, title)).toBeVisible();
      await tabAndPress(c, editTaskControl(c));
      await expect(button(c, 'Save changes')).toBeVisible();
      await setDateField(c, c.getByLabel('Deadline date'), '');
      await setDateField(c, c.getByLabel('Deadline time'), '');
      await tabAndPress(c, button(c, 'Save changes'));
      await expect(dialogNamed(c, 'Deadline is in the past')).toHaveCount(0);
      await expect(liveRegion(c)).toHaveText('Task saved');
      await expect(c.getByText('No deadline').first()).toBeVisible();
      await expect(c.getByText(/^Overdue$/i)).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'task details – deadline removed' });
    });

    const requestPanel = (page: Page) => sectionOf(page, 'Deadline requests');

    async function submitDeadlineRequest(date: string, reason: string, label: string): Promise<void> {
      await a.reload();
      await expect(heading1(a, title)).toBeVisible();
      const panel = requestPanel(a);
      await expect(panel).toBeVisible();
      await setDateField(a, panel.getByLabel('Proposed date'), date);
      await expect(panel.getByLabel('Proposed time')).toBeVisible();
      await expect(panel.getByLabel('Time zone')).toBeVisible();
      await tabTo(a, panel.getByLabel('Reason (required)'));
      await a.keyboard.type(reason);
      await tabAndPress(a, panel.getByRole('button', { name: 'Submit request' }));
      await expect(panel.getByText('Pending', { exact: true })).toBeVisible();
      await expectNoAxeViolations(a, { label: `deadline request – pending (${label})` });
      // A pending request does not change the effective deadline.
      await expect(a.getByText('No deadline').first()).toBeVisible();
    }

    await test.step('assignee requests a deadline; creator approves it', async () => {
      await submitDeadlineRequest(isoDate(30), 'Supplier figures arrive at month end', 'approve');

      await c.reload();
      await expect(heading1(c, title)).toBeVisible();
      const panel = requestPanel(c);
      await expect(panel.getByText('Pending', { exact: true })).toBeVisible();
      await expect(panel.getByLabel('Proposed date')).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'deadline request – creator sees pending' });
      await tabAndPress(c, panel.getByRole('button', { name: /^Approve request for / }));
      await expect(panel.getByText('Approved', { exact: true })).toBeVisible();
      await expect(c.getByText('No deadline')).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'deadline request – approved' });

      await a.reload();
      await expect(requestPanel(a).getByText('Approved', { exact: true })).toBeVisible();
      await expect(a.getByText('No deadline')).toHaveCount(0);
    });

    await test.step('assignee requests another deadline; creator declines it', async () => {
      await submitDeadlineRequest(isoDate(45), 'Review meeting moved', 'decline');

      await c.reload();
      await expect(heading1(c, title)).toBeVisible();
      const panel = requestPanel(c);
      await tabAndPress(c, panel.getByRole('button', { name: /^Decline request for / }));
      await expect(panel.getByText('Declined', { exact: true })).toBeVisible();
      await expect(panel.getByRole('button', { name: /^Approve request for / })).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'deadline request – declined' });
    });

    await test.step('assignee requests a third deadline and withdraws it; creator cannot withdraw', async () => {
      await submitDeadlineRequest(isoDate(50), 'Might need more time', 'withdraw');
      const panel = requestPanel(a);
      await expect(panel.getByRole('button', { name: /^Approve request for / })).toHaveCount(0);
      await tabAndPress(a, panel.getByRole('button', { name: /^Withdraw request for / }));
      await expect(panel.getByText('Withdrawn', { exact: true })).toBeVisible();
      await expectNoAxeViolations(a, { label: 'deadline request – withdrawn' });

      await c.reload();
      await expect(requestPanel(c).getByText('Withdrawn', { exact: true })).toBeVisible();
      await expect(requestPanel(c).getByRole('button', { name: /^Withdraw request for / })).toHaveCount(0);
    });

    await test.step('history records every transition in order', async () => {
      const history = historyList(c);
      await expect(history).toBeVisible();
      expect(await history.evaluate((element) => element.tagName)).toBe('OL');
      for (const text of [
        'In progress',
        'Blocked',
        blockedReason,
        'Done',
        'To do',
        'Approved',
        'Declined',
        'Withdrawn',
        DEMO_ACCOUNTS.assignee.displayName,
        DEMO_ACCOUNTS.creator.displayName,
      ]) {
        await expect(history).toContainText(text);
      }
      await expectNoAxeViolations(c, { label: 'task details – history' });
    });

    await test.step('creator reassigns to the replacement; the former assignee loses access', async () => {
      await tabAndPress(c, editTaskControl(c));
      await expect(button(c, 'Save changes')).toBeVisible();
      await chooseAssignee(c, 'replacement', DEMO_ACCOUNTS.replacement.email);
      await tabAndPress(c, button(c, 'Save changes'));
      await expect(liveRegion(c)).toHaveText('Task saved');
      await expect(c.getByText(DEMO_ACCOUNTS.replacement.displayName).first()).toBeVisible();
      await expect(historyList(c)).toContainText(DEMO_ACCOUNTS.replacement.displayName);

      await a.reload();
      await expectNotAvailable(a, title, 'task not available – former assignee');
      await expectTaskAbsentFromLists(a, title);
    });

    await test.step('replacement finds the task and sees its retained history', async () => {
      const r = replacement.page;
      await openFromList(r, 'Assigned to me', title);
      await expect(r.getByText('Collect supplier figures, compare prices and draft the review.')).toBeVisible();
      await expect(historyList(r)).toContainText(blockedReason);
      await expect(historyList(r)).toContainText('Approved');
      await expect(editTaskControl(r)).toHaveCount(0);
      await expectNoAxeViolations(r, { label: 'task details – replacement assignee' });
    });

    await test.step('outsider still cannot reach the task', async () => {
      await outsider.page.goto(`/tasks/${taskId}`);
      await expectNotAvailable(outsider.page, title, 'task not available – outsider after reassignment');
    });

    for (const session of [creator, assignee, replacement, outsider]) await session.context.close();
  });

  test('only the creator cancels with a reason and reopens a cancelled task – keyboard only', async ({ browser }, testInfo) => {
    const title = uniqueTitle('Book venue for team offsite', testInfo.project.name);
    const cancelReason = 'Offsite postponed to next quarter';
    const { creator, assignee, outsider } = await openSessions(browser, ['creator', 'assignee', 'outsider']);
    if (!creator || !assignee || !outsider) throw new Error('Missing demo session');
    const c = creator.page;
    const a = assignee.page;
    let taskId = '';

    await test.step('creator creates the task for the assignee found by email', async () => {
      taskId = await createTask(c, {
        title,
        assignee: 'assignee',
        assigneeQuery: DEMO_ACCOUNTS.assignee.email,
        description: 'Compare three venues.',
      });
      await expectStatus(c, 'To do');
      await expectNoAxeViolations(c, { label: 'task details – cancellable task' });
    });

    await test.step('assignee has no cancel action', async () => {
      await openFromList(a, 'Assigned to me', title);
      await expect(button(a, 'Cancel task')).toHaveCount(0);
    });

    await test.step('creator cancels with a required reason', async () => {
      const cancelButton = button(c, 'Cancel task');
      await tabAndPress(c, cancelButton);
      const dialog = dialogNamed(c, 'Cancel task');
      await expect(dialog).toBeVisible();
      const reason = dialog.getByLabel('Reason (required)');
      await expect(reason).toBeFocused();
      await expectNoAxeViolations(c, { include: DIALOG_SELECTOR, label: 'dialog – cancel task' });

      await c.keyboard.press('Escape');
      await expect(dialog).toBeHidden();
      await expect(cancelButton).toBeFocused();
      await expectStatus(c, 'To do');

      await c.keyboard.press('Enter');
      await expect(dialog).toBeVisible();
      await tabAndPress(c, dialog.getByRole('button', { name: 'Keep task' }));
      await expect(dialog).toBeHidden();
      await expect(cancelButton).toBeFocused();

      await c.keyboard.press('Space');
      await expect(dialog).toBeVisible();
      await tabAndPress(c, dialog.getByRole('button', { name: 'Confirm cancellation' }));
      await expect(dialog).toBeVisible();
      await expect(reason).toHaveAttribute('aria-invalid', 'true');
      await expectNoAxeViolations(c, { include: DIALOG_SELECTOR, label: 'dialog – cancel task, empty reason' });

      await tabTo(c, reason);
      await c.keyboard.type(cancelReason);
      await tabAndPress(c, dialog.getByRole('button', { name: 'Confirm cancellation' }));
      await expect(dialog).toBeHidden();
      await expect(liveRegion(c)).toHaveText('Status changed to Cancelled');
      await expectStatus(c, 'Cancelled');
      await expect(c.getByText(cancelReason).first()).toBeVisible();
      await expect(c.getByText(/^Overdue$/i)).toHaveCount(0);
      await expectNoAxeViolations(c, { label: 'task details – cancelled' });
    });

    await test.step('assignee keeps access but cannot reopen or change the cancelled task', async () => {
      await a.reload();
      await expect(heading1(a, title)).toBeVisible();
      await expectStatus(a, 'Cancelled');
      await expect(a.getByText('Compare three venues.')).toBeVisible();
      for (const name of ['Reopen', 'Start progress', 'Mark blocked', 'Mark done', 'Cancel task']) {
        await expect(button(a, name)).toHaveCount(0);
      }
      await expectNoAxeViolations(a, { label: 'task details – cancelled, assignee' });
    });

    await test.step('outsider cannot reach the cancelled task', async () => {
      await outsider.page.goto(`/tasks/${taskId}`);
      await expectNotAvailable(outsider.page, title, 'task not available – outsider, cancelled task');
    });

    await test.step('creator reopens the cancelled task; history keeps the cancellation', async () => {
      await tabAndPress(c, button(c, 'Reopen'));
      await expect(liveRegion(c)).toHaveText('Status changed to To do');
      await expectStatus(c, 'To do');
      await expect(c.getByText('No deadline').first()).toBeVisible();
      const history = historyList(c);
      await expect(history).toContainText('Cancelled');
      await expect(history).toContainText(cancelReason);
      await expectNoAxeViolations(c, { label: 'task details – reopened after cancellation' });

      await a.reload();
      await expectStatus(a, 'To do');
      await expect(button(a, 'Start progress')).toBeVisible();
    });

    for (const session of [creator, assignee, outsider]) await session.context.close();
  });
});
