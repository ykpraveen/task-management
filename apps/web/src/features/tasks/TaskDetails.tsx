import type { ApiClients, Task, TaskApi } from '@task-management/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { Dialog } from '../../a11y/Dialog';
import { useAnnouncer } from '../../a11y/LiveRegion';
import { OverdueText, PRIORITY_LABELS, STATUS_LABELS, StatusText } from '../../a11y/StatusText';
import { useCurrentUser, type SessionController } from '../../auth/session';
import {
  ApiError,
  applyTaskUpdate,
  mutationHeaders,
  problemCode,
  unwrap,
  useIdempotencyKeys,
  useTask,
  useViewerId,
} from './api';
import { DeadlineRequestPanel, REASON_MAX } from './DeadlineRequestPanel';
import { CONFLICT_MESSAGE, ConflictNotice, PastDeadlineDialog, failureMessage } from './notices';
import { TaskForm } from './TaskForm';
import { formatDeadline, formatTimestamp } from './time';

type OpenStatus = TaskApi.components['schemas']['OpenStatus'];

type Mutation =
  | { kind: 'status'; status: OpenStatus; reason?: string }
  | { kind: 'reopen'; confirmPastDeadline: boolean }
  | { kind: 'cancel'; reason: string };

type ReasonDialog = 'blocked' | 'cancel';

type Action = { label: string; mutation: Mutation } | { label: string; dialog: ReasonDialog };

const REASON_DIALOGS: Record<ReasonDialog, { title: string; confirm: string; keep: string }> = {
  blocked: { title: 'Mark task blocked', confirm: 'Mark blocked', keep: 'Keep current status' },
  cancel: { title: 'Cancel task', confirm: 'Confirm cancellation', keep: 'Keep task' },
};

const NOT_CHANGED = 'Status not changed.';

/** Status actions offered for the task's status and the caller's permissions. */
function statusActions(task: Task): Action[] {
  const { permissions: can, status } = task;
  const open = status === 'todo' || status === 'in_progress' || status === 'blocked';
  const actions: Action[] = [];
  if (open && can.changeStatus) {
    if (status !== 'in_progress')
      actions.push({ label: 'Start progress', mutation: { kind: 'status', status: 'in_progress' } });
    if (status === 'in_progress')
      actions.push({ label: 'Stop progress', mutation: { kind: 'status', status: 'todo' } });
    if (status === 'blocked')
      actions.push({ label: 'Move back to To do', mutation: { kind: 'status', status: 'todo' } });
    if (status !== 'blocked') actions.push({ label: 'Mark blocked', dialog: 'blocked' });
    actions.push({ label: 'Mark done', mutation: { kind: 'status', status: 'done' } });
  }
  if (open && can.cancel) actions.push({ label: 'Cancel task', dialog: 'cancel' });
  if (!open && can.reopen) actions.push({ label: 'Reopen', mutation: { kind: 'reopen', confirmPastDeadline: false } });
  return actions;
}

export interface TaskDetailsProps {
  api: ApiClients;
  session: SessionController;
  /** Canonical decimal string; never converted to a number. */
  taskId: string;
}

export function TaskDetails({ api, session, taskId }: TaskDetailsProps) {
  const userId = useViewerId(session);
  return userId ? <TaskLoader api={api} session={session} userId={userId} taskId={taskId} /> : null;
}

function TaskLoader({ api, session, userId, taskId }: TaskDetailsProps & { userId: string }) {
  const query = useTask(api, userId, taskId);

  if (query.isPending) {
    return (
      <>
        <h1>Task</h1>
        <p>Loading the task…</p>
      </>
    );
  }
  if (query.isError) {
    const status = query.error instanceof ApiError ? query.error.status : 0;
    // Outsiders get 404 for tasks that exist; say the same thing for both.
    return status === 404 || status === 403 ? (
      <>
        <h1>Task not available</h1>
        <p>The task does not exist or you do not have access to it.</p>
      </>
    ) : (
      <>
        <h1>Task</h1>
        <p>The task could not be loaded.</p>
        <button type="button" onClick={() => void query.refetch()}>
          Try again
        </button>
      </>
    );
  }
  return (
    <TaskDetailsView
      api={api}
      session={session}
      userId={userId}
      task={query.data}
      reload={async () => void (await query.refetch())}
    />
  );
}

interface TaskDetailsViewProps {
  api: ApiClients;
  session: SessionController;
  userId: string;
  task: Task;
  reload: () => Promise<void>;
}

/**
 * Task facts and status controls. State is text, never color alone; nothing changes or is
 * announced until the server confirms, and every mutation sends the current version.
 */
function TaskDetailsView({ api, session, userId, task, reload }: TaskDetailsViewProps) {
  const queryClient = useQueryClient();
  const user = useCurrentUser(session);
  const { announce } = useAnnouncer();
  const keys = useIdempotencyKeys();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const reasonId = useId();
  const editHeadingId = useId();

  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [dialog, setDialog] = useState<ReasonDialog | null>(null);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);
  const [confirmingReopen, setConfirmingReopen] = useState(false);
  const [editing, setEditing] = useState(false);

  // When a confirmed change removes the focused control (e.g. Start progress), keep focus on the page.
  useEffect(() => {
    if (document.activeElement === document.body || document.activeElement === null) headingRef.current?.focus();
  }, [task.status]);

  function showConflict() {
    setConflict(true);
    announce(CONFLICT_MESSAGE);
  }

  async function mutate(mutation: Mutation) {
    if (pendingRef.current) return;
    const key = keys.keyFor({ ...mutation, id: task.id, version: task.version });
    const params = { path: { id: task.id }, header: mutationHeaders(task.version, key) };
    pendingRef.current = true;
    setPending(true);
    setActionError(null);
    try {
      const result =
        mutation.kind === 'status'
          ? await api.tasks.POST('/tasks/{id}/status', {
              params,
              body: mutation.reason
                ? { status: mutation.status, reason: mutation.reason }
                : { status: mutation.status },
            })
          : mutation.kind === 'reopen'
            ? await api.tasks.POST('/tasks/{id}/reopen', {
                params,
                body: { confirmPastDeadline: mutation.confirmPastDeadline },
              })
            : await api.tasks.POST('/tasks/{id}/cancel', { params, body: { reason: mutation.reason } });
      const updated = unwrap(result);
      keys.reset();
      applyTaskUpdate(queryClient, userId, updated);
      setDialog(null);
      setConflict(false);
      announce(`Status changed to ${STATUS_LABELS[updated.status]}`);
    } catch (error) {
      const code = problemCode(error);
      if (mutation.kind === 'reopen' && code === 'PAST_DEADLINE_CONFIRMATION_REQUIRED') {
        setConfirmingReopen(true);
      } else if (code === 'STALE_VERSION') {
        setDialog(null);
        showConflict();
      } else {
        const message = failureMessage(error, NOT_CHANGED);
        setActionError(message);
        announce(message);
      }
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  function openDialog(which: ReasonDialog) {
    setReason('');
    setReasonError(null);
    setActionError(null);
    setDialog(which);
  }

  function submitReason(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pendingRef.current || !dialog) return;
    const text = reason.trim();
    if (text === '') {
      setReasonError('Enter a reason');
      reasonRef.current?.focus();
      return;
    }
    if (text.length > REASON_MAX) {
      setReasonError(`Reason must be ${REASON_MAX.toLocaleString('en')} characters or fewer`);
      reasonRef.current?.focus();
      return;
    }
    setReasonError(null);
    void mutate(
      dialog === 'blocked' ? { kind: 'status', status: 'blocked', reason: text } : { kind: 'cancel', reason: text },
    );
  }

  async function onReload() {
    await reload();
    setConflict(false);
    setActionError(null);
    keys.reset();
  }

  const actions = statusActions(task);
  const busy = pending || undefined;
  const reasonDialog = dialog ? REASON_DIALOGS[dialog] : null;

  return (
    <article className="stack task-details">
      <h1 ref={headingRef} tabIndex={-1}>
        {task.title}
      </h1>
      {conflict ? (
        <ConflictNotice
          onReload={() => void onReload()}
          detail="Reload to see the current task before you change it again."
        />
      ) : null}

      <dl className="task-facts">
        <dt>Status</dt>
        <dd>
          <StatusText status={task.status} />
        </dd>
        {task.status === 'blocked' && task.blockedReason ? (
          <>
            <dt>Blocked reason</dt>
            <dd>{task.blockedReason}</dd>
          </>
        ) : null}
        {task.status === 'cancelled' && task.cancellationReason ? (
          <>
            <dt>Cancellation reason</dt>
            <dd>{task.cancellationReason}</dd>
          </>
        ) : null}
        <dt>Deadline</dt>
        <dd>
          {task.dueAt && task.dueTimeZone ? formatDeadline(task.dueAt, task.dueTimeZone) : 'No deadline'}
          {task.isOverdue ? ' ' : null}
          <OverdueText overdue={task.isOverdue} />
        </dd>
        <dt>Priority</dt>
        <dd>{PRIORITY_LABELS[task.priority]}</dd>
        <dt>Assignee</dt>
        <dd>
          {task.assignee.displayName} ({task.assignee.email})
        </dd>
        <dt>Created by</dt>
        <dd>
          {task.creator.displayName} ({task.creator.email})
        </dd>
        <dt>Created</dt>
        <dd>{formatTimestamp(task.createdAt, user?.timeZone)}</dd>
        {task.completedAt ? (
          <>
            <dt>Completed</dt>
            <dd>{formatTimestamp(task.completedAt, user?.timeZone)}</dd>
          </>
        ) : null}
      </dl>

      <section className="stack" aria-label="Description">
        <h2>Description</h2>
        <p className="task-description">{task.description === '' ? 'No description.' : task.description}</p>
      </section>

      {actions.length > 0 ? (
        <section className="stack" aria-label="Status actions">
          <h2>Change status</h2>
          <div className="actions">
            {actions.map((action) => (
              <button
                key={action.label}
                type="button"
                aria-disabled={busy}
                onClick={() => ('dialog' in action ? openDialog(action.dialog) : void mutate(action.mutation))}
              >
                {action.label}
              </button>
            ))}
          </div>
          {actionError && !dialog ? <p className="field-error">{actionError}</p> : null}
        </section>
      ) : null}

      {task.permissions.edit ? (
        <section className="stack" aria-labelledby={editHeadingId}>
          <h2 id={editHeadingId}>Edit task</h2>
          {editing ? (
            <>
              <TaskForm api={api} session={session} task={task} onSaved={() => setEditing(false)} />
              <div className="actions">
                <button type="button" onClick={() => setEditing(false)}>
                  Stop editing
                </button>
              </div>
            </>
          ) : (
            <div className="actions">
              <button type="button" onClick={() => setEditing(true)}>
                Edit task details
              </button>
            </div>
          )}
        </section>
      ) : null}

      <DeadlineRequestPanel api={api} session={session} userId={userId} task={task} onConflict={showConflict} />

      <Dialog
        open={reasonDialog !== null}
        title={reasonDialog?.title ?? ''}
        onClose={() => setDialog(null)}
        initialFocusRef={reasonRef}
      >
        {reasonDialog ? (
          <form className="stack" noValidate onSubmit={submitReason}>
            <div className="field">
              <label htmlFor={reasonId}>Reason (required)</label>
              <textarea
                ref={reasonRef}
                id={reasonId}
                rows={4}
                required
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                aria-invalid={reasonError ? true : undefined}
                aria-describedby={reasonError ? `${reasonId}-error` : undefined}
              />
              {reasonError ? (
                <p id={`${reasonId}-error`} className="field-error">
                  {reasonError}
                </p>
              ) : null}
            </div>
            {actionError ? <p className="field-error">{actionError}</p> : null}
            <div className="actions">
              <button type="submit" className="primary" aria-disabled={busy}>
                {reasonDialog.confirm}
              </button>
              <button type="button" onClick={() => setDialog(null)}>
                {reasonDialog.keep}
              </button>
            </div>
          </form>
        ) : null}
      </Dialog>

      <PastDeadlineDialog
        open={confirmingReopen}
        closeLabel="Do not reopen"
        onClose={() => setConfirmingReopen(false)}
        onConfirm={() => {
          setConfirmingReopen(false);
          void mutate({ kind: 'reopen', confirmPastDeadline: true });
        }}
      />
    </article>
  );
}
