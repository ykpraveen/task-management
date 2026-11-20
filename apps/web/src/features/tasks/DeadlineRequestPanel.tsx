import type { ApiClients, Task } from '@task-management/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { ErrorSummary, type SummaryError } from '../../a11y/ErrorSummary';
import { useAnnouncer } from '../../a11y/LiveRegion';
import { browserTimeZone, supportedTimeZones } from '../../auth/ProfileSettings';
import { useCurrentUser, type SessionController } from '../../auth/session';
import { useAppForm } from '../../form';
import {
  ApiError,
  applyTaskUpdate,
  mutationHeaders,
  problemCode,
  taskKeys,
  unwrap,
  useDeadlineRequests,
  useIdempotencyKeys,
  type DeadlineRequest,
} from './api';
import { PastDeadlineDialog, failureMessage } from './notices';
import { formatDeadline, formatTimestamp, isTimeInput, zoneLabel, zonedToInstant } from './time';

export const REASON_MAX = 2_000;

export const REQUEST_STATE_LABELS: Record<DeadlineRequest['state'], string> = {
  pending: 'Pending',
  approved: 'Approved',
  declined: 'Declined',
  withdrawn: 'Withdrawn',
  superseded: 'Superseded',
};

type Decision = 'approve' | 'decline' | 'withdraw';

const DECISION_TEXT: Record<Decision, { done: string; notDone: string }> = {
  approve: { done: 'Deadline request approved', notDone: 'The request was not approved.' },
  decline: { done: 'Deadline request declined', notDone: 'The request was not declined.' },
  withdraw: { done: 'Deadline request withdrawn', notDone: 'The request was not withdrawn.' },
};

type FieldName = 'date' | 'time' | 'zone' | 'reason';
type FieldErrors = Partial<Record<FieldName, string>>;
const FIELDS: FieldName[] = ['date', 'time', 'zone', 'reason'];
const SERVER_FIELDS: Record<string, FieldName> = { proposedDueAt: 'date', proposedTimeZone: 'zone', reason: 'reason' };

interface RequestValues {
  date: string;
  time: string;
  zone: string;
  reason: string;
}

function validate(values: RequestValues): FieldErrors {
  const errors: FieldErrors = {};
  if (values.date === '') errors.date = 'Enter a proposed date';
  if (values.time !== '' && !isTimeInput(values.time)) errors.time = 'Enter the proposed time as hours and minutes';
  if (values.zone === '') errors.zone = 'Choose a time zone';
  const reason = values.reason.trim();
  if (reason === '') errors.reason = 'Enter a reason for the new deadline';
  else if (reason.length > REASON_MAX)
    errors.reason = `Reason must be ${REASON_MAX.toLocaleString('en')} characters or fewer`;
  return errors;
}

export interface DeadlineRequestPanelProps {
  api: ApiClients;
  session: SessionController;
  /** The viewer the page was opened for (see useViewerId). */
  userId: string;
  task: Task;
  /** A mutation answered 409 STALE_VERSION; the task page shows its conflict notice. */
  onConflict: () => void;
}

/**
 * Deadline requests for a task: the assignee proposes a new deadline, the creator approves or
 * declines, the requester may withdraw. Every mutation carries the task's current version.
 * Request state is shown as text and changes only after the server confirms.
 */
export function DeadlineRequestPanel({ api, session, userId, task, onConflict }: DeadlineRequestPanelProps) {
  const queryClient = useQueryClient();
  const user = useCurrentUser(session);
  const { announce } = useAnnouncer();
  const requests = useDeadlineRequests(api, userId, task.id);
  const keys = useIdempotencyKeys();
  const headingId = useId();

  const [pendingDecision, setPendingDecision] = useState<string | null>(null);
  const pendingRef = useRef(false);
  const [decisionError, setDecisionError] = useState<string | null>(null);
  const [confirmingApproval, setConfirmingApproval] = useState<DeadlineRequest | null>(null);

  async function decide(request: DeadlineRequest, decision: Decision, confirmPastDeadline = false) {
    if (pendingRef.current) return;
    pendingRef.current = true;
    setPendingDecision(request.id);
    setDecisionError(null);
    const key = keys.keyFor({ decision, requestId: request.id, version: task.version, confirmPastDeadline });
    const params = { path: { id: task.id, requestId: request.id }, header: mutationHeaders(task.version, key) };
    try {
      const result =
        decision === 'approve'
          ? await api.tasks.POST('/tasks/{id}/deadline-requests/{requestId}/approve', {
              params,
              body: { confirmPastDeadline },
            })
          : decision === 'decline'
            ? await api.tasks.POST('/tasks/{id}/deadline-requests/{requestId}/decline', { params })
            : await api.tasks.POST('/tasks/{id}/deadline-requests/{requestId}/withdraw', { params });
      const decided = unwrap(result);
      keys.reset();
      queryClient.setQueryData<DeadlineRequest[]>(taskKeys.deadlineRequests(userId, task.id), (old) =>
        old?.map((r) => (r.id === decided.request.id ? decided.request : r)),
      );
      applyTaskUpdate(queryClient, userId, decided.task);
      announce(DECISION_TEXT[decision].done);
    } catch (error) {
      const code = problemCode(error);
      if (decision === 'approve' && code === 'PAST_DEADLINE_CONFIRMATION_REQUIRED') {
        setConfirmingApproval(request);
      } else if (code === 'STALE_VERSION') {
        onConflict();
      } else {
        const message = failureMessage(error, DECISION_TEXT[decision].notDone);
        setDecisionError(message);
        announce(message);
      }
    } finally {
      pendingRef.current = false;
      setPendingDecision(null);
    }
  }

  const items = requests.data ?? [];

  return (
    <section className="stack deadline-requests" aria-labelledby={headingId}>
      <h2 id={headingId}>Deadline requests</h2>
      {requests.isPending ? (
        <p>Loading deadline requests…</p>
      ) : requests.isError ? (
        <p>Deadline requests could not be loaded.</p>
      ) : items.length === 0 ? (
        <p>No deadline requests.</p>
      ) : (
        <ul className="request-list">
          {items.map((request) => {
            const proposed = formatDeadline(request.proposedDueAt, request.proposedTimeZone);
            const pending = request.state === 'pending';
            const canDecide = pending && task.permissions.decideDeadlineRequest;
            const canWithdraw = pending && request.requester.id === userId;
            const busy = pendingDecision !== null || undefined;
            return (
              <li key={request.id} className="request">
                <p>
                  Proposed deadline <strong>{proposed}</strong>. State:{' '}
                  <span className={`request-state request-${request.state}`}>
                    {REQUEST_STATE_LABELS[request.state]}
                  </span>
                </p>
                <p>
                  Requested by {request.requester.displayName} on {formatTimestamp(request.createdAt, user?.timeZone)}.
                  Reason: {request.reason}
                </p>
                {canDecide || canWithdraw ? (
                  <div className="actions">
                    {canDecide ? (
                      <>
                        <button type="button" aria-disabled={busy} onClick={() => void decide(request, 'approve')}>
                          Approve request for {proposed}
                        </button>
                        <button type="button" aria-disabled={busy} onClick={() => void decide(request, 'decline')}>
                          Decline request for {proposed}
                        </button>
                      </>
                    ) : null}
                    {canWithdraw ? (
                      <button type="button" aria-disabled={busy} onClick={() => void decide(request, 'withdraw')}>
                        Withdraw request for {proposed}
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {decisionError ? <p className="field-error">{decisionError}</p> : null}
      {task.permissions.requestDeadline ? (
        <DeadlineRequestForm
          api={api}
          userId={userId}
          task={task}
          defaultZone={task.dueTimeZone ?? user?.timeZone ?? browserTimeZone() ?? 'UTC'}
          onConflict={onConflict}
        />
      ) : null}
      <PastDeadlineDialog
        open={confirmingApproval !== null}
        closeLabel="Do not approve"
        onClose={() => setConfirmingApproval(null)}
        onConfirm={() => {
          const request = confirmingApproval;
          setConfirmingApproval(null);
          if (request) void decide(request, 'approve', true);
        }}
      />
    </section>
  );
}

interface DeadlineRequestFormProps {
  api: ApiClients;
  userId: string;
  task: Task;
  defaultZone: string;
  onConflict: () => void;
}

function DeadlineRequestForm({ api, userId, task, defaultZone, onConflict }: DeadlineRequestFormProps) {
  const queryClient = useQueryClient();
  const { announce } = useAnnouncer();
  const keys = useIdempotencyKeys();
  const zones = useMemo(supportedTimeZones, []);
  const prefix = useId();
  const ids: Record<FieldName, string> = {
    date: `${prefix}-date`,
    time: `${prefix}-time`,
    zone: `${prefix}-zone`,
    reason: `${prefix}-reason`,
  };
  const hintId = `${prefix}-hint`;
  const defaults: RequestValues = { date: '', time: '', zone: defaultZone, reason: '' };
  const form = useAppForm({ defaultValues: defaults });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [banner, setBanner] = useState<string[]>([]);
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);

  const summary: SummaryError[] = FIELDS.flatMap((name) => {
    const message = fieldErrors[name];
    return message ? [{ fieldId: ids[name], message }] : [];
  });

  function describedBy(name: FieldName, hint?: string): string | undefined {
    return [hint, fieldErrors[name] ? `${ids[name]}-error` : undefined].filter(Boolean).join(' ') || undefined;
  }

  function fieldError(name: FieldName) {
    const message = fieldErrors[name];
    return message ? (
      <p id={`${ids[name]}-error`} className="field-error">
        {message}
      </p>
    ) : null;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pendingRef.current) return;
    const values = form.state.values;
    const errors = validate(values);
    setFieldErrors(errors);
    setBanner([]);
    setAttempt((n) => n + 1);
    if (Object.keys(errors).length > 0) return;

    const body = {
      proposedDueAt: zonedToInstant(values.date, values.time, values.zone),
      proposedTimeZone: values.zone,
      reason: values.reason.trim(),
    };
    const key = keys.keyFor({ taskId: task.id, version: task.version, body });
    pendingRef.current = true;
    setPending(true);
    try {
      const created = unwrap(
        await api.tasks.POST('/tasks/{id}/deadline-requests', {
          params: { path: { id: task.id }, header: mutationHeaders(task.version, key) },
          body,
        }),
      );
      keys.reset();
      queryClient.setQueryData<DeadlineRequest[]>(taskKeys.deadlineRequests(userId, task.id), (old) => [
        created,
        ...(old ?? []).filter((r) => r.id !== created.id),
      ]);
      // A new request supersedes an earlier pending one and adds a history entry.
      void queryClient.invalidateQueries({ queryKey: taskKeys.deadlineRequests(userId, task.id) });
      void queryClient.invalidateQueries({ queryKey: taskKeys.history(userId, task.id) });
      form.reset(defaults);
      setFieldErrors({});
      announce('Deadline request submitted');
    } catch (error) {
      const code = problemCode(error);
      if (code === 'STALE_VERSION') {
        onConflict();
      } else if (code === 'VALIDATION_FAILED' && error instanceof ApiError && error.problem?.fieldErrors?.length) {
        const mapped: FieldErrors = {};
        const unknown: string[] = [];
        for (const fe of error.problem.fieldErrors) {
          const name = Object.hasOwn(SERVER_FIELDS, fe.field) ? SERVER_FIELDS[fe.field] : undefined;
          if (name && !mapped[name]) mapped[name] = fe.message;
          else if (!name) unknown.push(fe.message);
        }
        setFieldErrors(mapped);
        setBanner(unknown);
        setAttempt((n) => n + 1);
      } else {
        const message = failureMessage(error, 'The deadline request was not submitted.');
        setBanner([message]);
        announce(message);
      }
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  return (
    <div className="stack">
      <h3>Request a new deadline</h3>
      <ErrorSummary errors={summary} focusKey={attempt} />
      {banner.length > 0 ? (
        <div className="notice form-banner">
          {banner.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      ) : null}
      <form className="stack" noValidate onSubmit={(event) => void onSubmit(event)} aria-label="Request a new deadline">
        <p id={hintId} className="field-hint">
          The proposed date and a reason are required. Without a time, the deadline is 23:59 on that date.
        </p>
        <form.Field name="date">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.date}>Proposed date</label>
              <input
                id={ids.date}
                type="date"
                required
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                onBlur={field.handleBlur}
                aria-invalid={fieldErrors.date ? true : undefined}
                aria-describedby={describedBy('date', hintId)}
              />
              {fieldError('date')}
            </div>
          )}
        </form.Field>
        <form.Field name="time">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.time}>Proposed time</label>
              <input
                id={ids.time}
                type="time"
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                onBlur={field.handleBlur}
                aria-invalid={fieldErrors.time ? true : undefined}
                aria-describedby={describedBy('time')}
              />
              {fieldError('time')}
            </div>
          )}
        </form.Field>
        <form.Field name="zone">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.zone}>Time zone</label>
              <select
                id={ids.zone}
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                aria-invalid={fieldErrors.zone ? true : undefined}
                aria-describedby={describedBy('zone')}
              >
                {(zones.includes(field.state.value) ? zones : [field.state.value, ...zones]).map((zone) => (
                  <option key={zone} value={zone}>
                    {zoneLabel(zone)}
                  </option>
                ))}
              </select>
              {fieldError('zone')}
            </div>
          )}
        </form.Field>
        <form.Field name="reason">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.reason}>Reason (required)</label>
              <textarea
                id={ids.reason}
                rows={3}
                required
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                onBlur={field.handleBlur}
                aria-invalid={fieldErrors.reason ? true : undefined}
                aria-describedby={describedBy('reason')}
              />
              {fieldError('reason')}
            </div>
          )}
        </form.Field>
        <div className="actions">
          <button type="submit" className="primary" aria-disabled={pending || undefined}>
            Submit request
          </button>
        </div>
      </form>
    </div>
  );
}
