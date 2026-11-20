import type { Task } from '@task-management/api-client';
import { createMemoryHistory } from '@tanstack/react-router';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { axe } from 'vitest-axe';
import { App } from '../../app';
import { CREATOR, createTestApp } from '../../test/fakeAuth';
import { TASK_ID, createFakeTaskApi, json, makeTask, problem, taskResponse } from '../../test/taskApi';

/** The home page with both lists, through the real router and generated client. */
async function renderHome(pages: Record<'assigned' | 'created', () => Response>) {
  const app = await createTestApp({ signedInAs: CREATOR, history: createMemoryHistory({ initialEntries: ['/'] }) });
  const server = createFakeTaskApi(app.fetchImpl);
  server.on('GET /tasks', ({ query }) => pages[query.get('view') as 'assigned' | 'created']());
  const view = render(<App queryClient={app.queryClient} router={app.router} />);
  await screen.findByRole('heading', { level: 1, name: 'My tasks' });
  return { ...app, ...view, server };
}

function page(items: Task[], nextCursor: string | null = null) {
  return () => json({ items, nextCursor });
}

function listSection(name: string): HTMLElement {
  return screen.getByRole('region', { name });
}

describe('TaskList', () => {
  it('shows a captioned table with column headers, text states and title links', async () => {
    const overdue = makeTask({ title: 'Send invoices', isOverdue: true, status: 'blocked', priority: 'high' });
    const undated = makeTask({ id: '9007199254740997', title: 'Tidy the wiki', dueAt: null, dueTimeZone: null });
    const { container } = await renderHome({ assigned: page([overdue, undated]), created: page([]) });

    const table = await within(listSection('Assigned to me')).findByRole('table', { name: 'Tasks assigned to me' });
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((th) => th.textContent),
    ).toEqual(['Title', 'Created by', 'Assignee', 'Priority', 'Status', 'Deadline']);
    const [first, second] = within(table).getAllByRole('row').slice(1);
    expect(within(first!).getByRole('link', { name: 'Send invoices' })).toHaveAttribute('href', `/tasks/${TASK_ID}`);
    expect(first).toHaveTextContent('High');
    expect(first).toHaveTextContent('Blocked');
    expect(first).toHaveTextContent('Overdue');
    expect(second).toHaveTextContent('No deadline');
    expect(second).not.toHaveTextContent('Overdue');
    expect(await within(listSection('Created by me')).findByText('You have not created any tasks.')).toBeVisible();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('says a failed list could not be loaded, distinctly from an empty one', async () => {
    const { container } = await renderHome({
      assigned: () => problem(503, 'DEPENDENCY_UNAVAILABLE'),
      created: page([]),
    });

    expect(
      await within(listSection('Assigned to me')).findByText('Your tasks could not be loaded.', {}, { timeout: 3000 }),
    ).toBeVisible();
    expect(within(listSection('Assigned to me')).getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(within(listSection('Created by me')).getByText('You have not created any tasks.')).toBeVisible();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('loads more with the cursor and moves focus to the first new row', async () => {
    const second = makeTask({ id: '9007199254740999', title: 'Second page task' });
    const { server } = await renderHome({ assigned: page([makeTask()], 'cursor-2'), created: page([]) });
    server.once('GET /tasks', () => page([second])());
    const user = userEvent.setup();

    await user.click(await within(listSection('Assigned to me')).findByRole('button', { name: 'Load more tasks' }));

    const link = await screen.findByRole('link', { name: 'Second page task' });
    await waitFor(() => expect(link).toHaveFocus());
    expect(server.calls('GET /tasks').at(-1)?.query.get('cursor')).toBe('cursor-2');
    expect(
      within(listSection('Assigned to me')).queryByRole('button', { name: 'Load more tasks' }),
    ).not.toBeInTheDocument();
  });

  it('opens the task details page from a title link', async () => {
    const { server } = await renderHome({ assigned: page([makeTask()]), created: page([]) });
    server.on(`GET /tasks/${TASK_ID}`, () => taskResponse(makeTask()));
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: 'Prepare quarterly report' }));

    const heading = await screen.findByRole('heading', { level: 1, name: 'Prepare quarterly report' });
    await waitFor(() => expect(heading).toHaveFocus());
  });
});
