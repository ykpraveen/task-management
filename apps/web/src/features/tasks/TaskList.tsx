import type { ApiClients } from '@task-management/api-client';
import { Link } from '@tanstack/react-router';
import { useEffect, useId, useRef, useState } from 'react';
import { OverdueText, PRIORITY_LABELS, StatusText } from '../../a11y/StatusText';
import type { SessionController } from '../../auth/session';
import { useTaskList, useViewerId, type TaskView } from './api';
import { formatDeadline } from './time';

const VIEW_TEXT: Record<TaskView, { heading: string; caption: string; empty: string }> = {
  assigned: {
    heading: 'Assigned to me',
    caption: 'Tasks assigned to me',
    empty: 'No tasks are assigned to you.',
  },
  created: {
    heading: 'Created by me',
    caption: 'Tasks I created',
    empty: 'You have not created any tasks.',
  },
};

export interface TaskListProps {
  api: ApiClients;
  session: SessionController;
  view: TaskView;
}

/**
 * One personal task list as a semantic table. Every state is text: status, priority,
 * deadline or "No deadline", and Overdue. Empty and failed lists say different things.
 * Load more keeps focus on the first newly loaded row.
 */
export function TaskList({ api, session, view }: TaskListProps) {
  const userId = useViewerId(session);
  return userId ? <ViewerTaskList api={api} userId={userId} view={view} /> : null;
}

function ViewerTaskList({ api, userId, view }: { api: ApiClients; userId: string; view: TaskView }) {
  const list = useTaskList(api, userId, view);
  const headingId = useId();
  const bodyRef = useRef<HTMLTableSectionElement>(null);
  const text = VIEW_TEXT[view];
  const tasks = list.data?.pages.flatMap((page) => page.items) ?? [];
  // Index of the first row of a requested page; focused once that row has rendered.
  const [focusRow, setFocusRow] = useState<number | null>(null);

  useEffect(() => {
    if (focusRow === null || tasks.length <= focusRow) return;
    bodyRef.current?.querySelectorAll<HTMLAnchorElement>('a')[focusRow]?.focus();
    setFocusRow(null);
  }, [focusRow, tasks.length]);

  async function loadMore() {
    if (list.isFetchingNextPage) return;
    const shown = tasks.length;
    const result = await list.fetchNextPage();
    if (!result.isError) setFocusRow(shown);
  }

  let content;
  if (list.isPending) {
    content = <p>Loading tasks…</p>;
  } else if (list.isError && tasks.length === 0) {
    content = (
      <>
        <p>Your tasks could not be loaded.</p>
        <button type="button" onClick={() => void list.refetch()}>
          Try again
        </button>
      </>
    );
  } else if (tasks.length === 0) {
    content = <p>{text.empty}</p>;
  } else {
    content = (
      <>
        <div className="table-scroll">
          <table className="task-table">
            <caption className="visually-hidden">{text.caption}</caption>
            <thead>
              <tr>
                <th scope="col">Title</th>
                <th scope="col">Created by</th>
                <th scope="col">Assignee</th>
                <th scope="col">Priority</th>
                <th scope="col">Status</th>
                <th scope="col">Deadline</th>
              </tr>
            </thead>
            <tbody ref={bodyRef}>
              {tasks.map((task) => (
                <tr key={task.id}>
                  <th scope="row">
                    <Link to="/tasks/$taskId" params={{ taskId: task.id }}>
                      {task.title}
                    </Link>
                  </th>
                  <td>{task.creator.displayName}</td>
                  <td>{task.assignee.displayName}</td>
                  <td>{PRIORITY_LABELS[task.priority]}</td>
                  <td>
                    <StatusText status={task.status} />
                  </td>
                  <td>
                    {task.dueAt && task.dueTimeZone ? formatDeadline(task.dueAt, task.dueTimeZone) : 'No deadline'}
                    {task.isOverdue ? ' ' : null}
                    <OverdueText overdue={task.isOverdue} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {list.isFetchNextPageError ? <p className="field-error">More tasks could not be loaded. Try again.</p> : null}
        {list.hasNextPage ? (
          <button type="button" aria-disabled={list.isFetchingNextPage || undefined} onClick={() => void loadMore()}>
            Load more tasks
          </button>
        ) : null}
      </>
    );
  }

  return (
    <section className="stack task-list" aria-labelledby={headingId}>
      <h2 id={headingId}>{text.heading}</h2>
      {content}
    </section>
  );
}
