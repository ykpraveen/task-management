import type { ApiClients, Priority, Task, TaskApi, UserSummary } from '@task-management/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from 'react';
import { Combobox, type ComboboxOption } from '../../a11y/Combobox';
import { ErrorSummary, type SummaryError } from '../../a11y/ErrorSummary';
import { useAnnouncer } from '../../a11y/LiveRegion';
import { PRIORITY_LABELS } from '../../a11y/StatusText';
import { browserTimeZone, supportedTimeZones } from '../../auth/ProfileSettings';
import { useCurrentUser, type SessionController } from '../../auth/session';
import { useAppForm } from '../../form';
import {
  applyTaskUpdate,
  mutationHeaders,
  problemCode,
  searchUsers,
  taskKeys,
  unwrap,
  useIdempotencyKeys,
  ApiError,
} from './api';
import { CONFLICT_MESSAGE, ConflictNotice, PastDeadlineDialog, failureMessage } from './notices';
import { instantToZoned, isDateInput, isTimeInput, zoneLabel, zonedToInstant } from './time';

export const TITLE_MAX = 200;
export const DESCRIPTION_MAX = 20_000;

type TaskCreate = TaskApi.components['schemas']['TaskCreate'];
type TaskPatch = TaskApi.components['schemas']['TaskPatch'];

interface TaskFormValues {
  title: string;
  assignee: ComboboxOption | null;
  description: string;
  priority: Priority;
  dueDate: string;
  dueTime: string;
  dueTimeZone: string;
}

type FieldName = 'title' | 'assignee' | 'description' | 'priority' | 'dueDate' | 'dueTimeZone';

/** Field order for the error summary. */
const FIELDS: FieldName[] = ['title', 'assignee', 'description', 'priority', 'dueDate', 'dueTimeZone'];

/** Allowlisted server field paths; any other path goes to the banner, never to a field. */
const SERVER_FIELDS: Record<string, FieldName> = {
  title: 'title',
  assigneeId: 'assignee',
  description: 'description',
  priority: 'priority',
  dueAt: 'dueDate',
  dueTimeZone: 'dueTimeZone',
};

type FieldErrors = Partial<Record<FieldName, string>>;

export function userOption(user: UserSummary): ComboboxOption {
  return { id: user.id, label: user.displayName, detail: user.email };
}

function valuesFrom(task: Task | undefined, defaultZone: string): TaskFormValues {
  const due = task?.dueAt && task.dueTimeZone ? instantToZoned(task.dueAt, task.dueTimeZone) : null;
  return {
    title: task?.title ?? '',
    assignee: task ? userOption(task.assignee) : null,
    description: task?.description ?? '',
    priority: task?.priority ?? 'normal',
    dueDate: due?.date ?? '',
    dueTime: due?.time ?? '',
    dueTimeZone: task?.dueTimeZone ?? defaultZone,
  };
}

function validate(values: TaskFormValues): FieldErrors {
  const errors: FieldErrors = {};
  const title = values.title.trim();
  if (title === '') errors.title = 'Enter a title';
  else if (title.length > TITLE_MAX) errors.title = `Title must be ${TITLE_MAX} characters or fewer`;
  if (!values.assignee) errors.assignee = 'Choose an assignee from the search results';
  if (values.description.length > DESCRIPTION_MAX)
    errors.description = `Description must be ${DESCRIPTION_MAX.toLocaleString('en')} characters or fewer`;
  if (values.dueDate === '' && values.dueTime !== '')
    errors.dueDate = 'Enter a deadline date, or clear the deadline time';
  else if (values.dueDate !== '' && !isDateInput(values.dueDate))
    errors.dueDate = 'Enter the deadline date as a valid date';
  else if (values.dueTime !== '' && !isTimeInput(values.dueTime))
    errors.dueDate = 'Enter the deadline time as hours and minutes';
  if (values.dueDate !== '' && values.dueTimeZone === '') errors.dueTimeZone = 'Choose a time zone for the deadline';
  return errors;
}

function deadlineOf(values: TaskFormValues): { dueAt: string | null; dueTimeZone: string | null } {
  return values.dueDate === ''
    ? { dueAt: null, dueTimeZone: null }
    : { dueAt: zonedToInstant(values.dueDate, values.dueTime, values.dueTimeZone), dueTimeZone: values.dueTimeZone };
}

function createBody(values: TaskFormValues, confirmPastDeadline: boolean): TaskCreate {
  return {
    title: values.title.trim(),
    assigneeId: values.assignee?.id ?? '',
    description: values.description,
    priority: values.priority,
    ...deadlineOf(values),
    confirmPastDeadline,
  };
}

/** Only the fields that differ from what the form was opened with. */
function patchBody(values: TaskFormValues, base: TaskFormValues, confirmPastDeadline: boolean): TaskPatch {
  const patch: TaskPatch = { confirmPastDeadline };
  if (values.title.trim() !== base.title.trim()) patch.title = values.title.trim();
  if (values.description !== base.description) patch.description = values.description;
  if (values.priority !== base.priority) patch.priority = values.priority;
  if (values.assignee && values.assignee.id !== base.assignee?.id) patch.assigneeId = values.assignee.id;
  const deadlineChanged =
    values.dueDate !== base.dueDate ||
    values.dueTime !== base.dueTime ||
    (values.dueDate !== '' && values.dueTimeZone !== base.dueTimeZone);
  if (deadlineChanged) Object.assign(patch, deadlineOf(values));
  return patch;
}

export interface TaskFormProps {
  api: ApiClients;
  session: SessionController;
  /** The task to edit; without it the form creates a task. */
  task?: Task;
  /** Receives the server's task after a confirmed save. */
  onSaved?: (task: Task) => void;
}

/**
 * Create or edit a task. Field state lives in TanStack Form; the server stays authoritative.
 * The form keeps the version it was opened with until a confirmed save or an explicit
 * Reload, so a background refresh can never silently adopt a newer version under the
 * user's edits. Nothing is announced or reported as saved before a 2xx response.
 */
export function TaskForm({ api, session, task, onSaved }: TaskFormProps) {
  const queryClient = useQueryClient();
  const user = useCurrentUser(session);
  const { announce } = useAnnouncer();
  const keys = useIdempotencyKeys();
  const zones = useMemo(supportedTimeZones, []);
  const defaultZone = user?.timeZone ?? browserTimeZone() ?? 'UTC';
  const editing = task !== undefined;

  const prefix = useId();
  const ids: Record<FieldName | 'dueTime', string> = {
    title: `${prefix}-title`,
    assignee: `${prefix}-assignee`,
    description: `${prefix}-description`,
    priority: `${prefix}-priority`,
    dueDate: `${prefix}-due-date`,
    dueTime: `${prefix}-due-time`,
    dueTimeZone: `${prefix}-due-zone`,
  };

  const [base, setBase] = useState(() => valuesFrom(task, defaultZone));
  const [version, setVersion] = useState(task?.version);
  const form = useAppForm({ defaultValues: base });

  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [banner, setBanner] = useState<string[]>([]);
  const [attempt, setAttempt] = useState(0);
  const [pending, setPending] = useState(false);
  const pendingRef = useRef(false);
  const [conflict, setConflict] = useState(false);
  const [confirmingPast, setConfirmingPast] = useState(false);
  const [focusDeadline, setFocusDeadline] = useState(0);

  // Runs after the dialog's own cleanup has returned focus to the submit button.
  useEffect(() => {
    if (focusDeadline > 0) document.getElementById(ids.dueDate)?.focus();
  }, [focusDeadline, ids.dueDate]);

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

  async function send(confirmPastDeadline: boolean) {
    if (pendingRef.current) return;
    const values = form.state.values;
    pendingRef.current = true;
    setPending(true);
    setBanner([]);
    try {
      let saved: Task;
      if (task && version) {
        const body = patchBody(values, base, confirmPastDeadline);
        const key = keys.keyFor({ op: 'update', id: task.id, version, body });
        saved = unwrap(
          await api.tasks.PATCH('/tasks/{id}', {
            params: { path: { id: task.id }, header: mutationHeaders(version, key) },
            body,
          }),
        );
      } else {
        const body = createBody(values, confirmPastDeadline);
        const key = keys.keyFor({ op: 'create', body });
        saved = unwrap(await api.tasks.POST('/tasks', { params: { header: { 'Idempotency-Key': key } }, body }));
      }
      keys.reset();
      if (user) applyTaskUpdate(queryClient, user.id, saved);
      const next = editing ? valuesFrom(saved, defaultZone) : valuesFrom(undefined, defaultZone);
      setBase(next);
      form.reset(next);
      setVersion(editing ? saved.version : undefined);
      setFieldErrors({});
      setConflict(false);
      announce(editing ? 'Task saved' : 'Task created');
      onSaved?.(saved);
    } catch (error) {
      handleFailure(error);
    } finally {
      pendingRef.current = false;
      setPending(false);
    }
  }

  function handleFailure(error: unknown) {
    const code = problemCode(error);
    if (code === 'PAST_DEADLINE_CONFIRMATION_REQUIRED') {
      setConfirmingPast(true);
      return;
    }
    if (code === 'STALE_VERSION') {
      setConflict(true);
      announce(CONFLICT_MESSAGE);
      return;
    }
    if (code === 'VALIDATION_FAILED' && error instanceof ApiError) {
      const mapped: FieldErrors = {};
      const unknown: string[] = [];
      for (const fe of error.problem?.fieldErrors ?? []) {
        const name = Object.hasOwn(SERVER_FIELDS, fe.field) ? SERVER_FIELDS[fe.field] : undefined;
        if (name && !mapped[name]) mapped[name] = fe.message;
        else if (!name) unknown.push(fe.message);
      }
      if (Object.keys(mapped).length > 0 || unknown.length > 0) {
        setFieldErrors(mapped);
        setBanner(unknown);
        setAttempt((n) => n + 1);
        return;
      }
    }
    const message = failureMessage(error, editing ? 'Your changes were not saved.' : 'The task was not created.');
    setBanner([message]);
    announce(message);
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pendingRef.current) return;
    const errors = validate(form.state.values);
    setFieldErrors(errors);
    setBanner([]);
    setAttempt((n) => n + 1);
    if (Object.keys(errors).length > 0) return;
    void send(false);
  }

  /** Explicit discard: load the current task and start over from it. */
  async function reload() {
    if (!task) return;
    try {
      const latest = unwrap(await api.tasks.GET('/tasks/{id}', { params: { path: { id: task.id } } }));
      if (user) queryClient.setQueryData(taskKeys.detail(user.id, latest.id), latest);
      const next = valuesFrom(latest, defaultZone);
      setBase(next);
      form.reset(next);
      setVersion(latest.version);
      setConflict(false);
      setFieldErrors({});
      setBanner([]);
      keys.reset();
      announce('Loaded the current version of the task');
    } catch (error) {
      setBanner([failureMessage(error, 'The task was not reloaded.')]);
    }
  }

  const zoneOptions = (value: string) => (value === '' || zones.includes(value) ? zones : [value, ...zones]);

  return (
    <>
      <ErrorSummary errors={summary} focusKey={attempt} />
      {banner.length > 0 ? (
        <div className="notice form-banner">
          {banner.map((message) => (
            <p key={message}>{message}</p>
          ))}
        </div>
      ) : null}
      {conflict ? (
        <ConflictNotice
          onReload={() => void reload()}
          detail="Reload to start again from the current version. Your unsaved changes in this form will be replaced."
        />
      ) : null}
      <form className="stack" noValidate onSubmit={onSubmit} aria-label={editing ? 'Edit task' : 'New task'}>
        <form.Field name="title">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.title}>Title (required)</label>
              <input
                id={ids.title}
                name="title"
                type="text"
                required
                autoComplete="off"
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                onBlur={field.handleBlur}
                aria-invalid={fieldErrors.title ? true : undefined}
                aria-describedby={describedBy('title', `${ids.title}-hint`)}
              />
              <p id={`${ids.title}-hint`} className="field-hint">
                Up to {TITLE_MAX} characters.
              </p>
              {fieldError('title')}
            </div>
          )}
        </form.Field>

        <form.Field name="assignee">
          {(field) => (
            <div className="field">
              <Combobox
                id={ids.assignee}
                label="Assignee"
                required
                selected={field.state.value}
                onSelect={(option) => field.handleChange(option)}
                search={async (query, signal) => (await searchUsers(api, query, signal)).map(userOption)}
                invalid={Boolean(fieldErrors.assignee)}
                {...(fieldErrors.assignee ? { describedBy: `${ids.assignee}-error` } : {})}
              />
              {fieldError('assignee')}
            </div>
          )}
        </form.Field>

        <form.Field name="description">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.description}>Description</label>
              <textarea
                id={ids.description}
                name="description"
                rows={6}
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value)}
                onBlur={field.handleBlur}
                aria-invalid={fieldErrors.description ? true : undefined}
                aria-describedby={describedBy('description', `${ids.description}-hint`)}
              />
              <p id={`${ids.description}-hint`} className="field-hint">
                Optional. Up to {DESCRIPTION_MAX.toLocaleString('en')} characters.
              </p>
              {fieldError('description')}
            </div>
          )}
        </form.Field>

        <form.Field name="priority">
          {(field) => (
            <div className="field">
              <label htmlFor={ids.priority}>Priority</label>
              <select
                id={ids.priority}
                name="priority"
                value={field.state.value}
                onChange={(event) => field.handleChange(event.target.value as Priority)}
                aria-invalid={fieldErrors.priority ? true : undefined}
                aria-describedby={describedBy('priority')}
              >
                {(Object.keys(PRIORITY_LABELS) as Priority[]).map((priority) => (
                  <option key={priority} value={priority}>
                    {PRIORITY_LABELS[priority]}
                  </option>
                ))}
              </select>
              {fieldError('priority')}
            </div>
          )}
        </form.Field>

        <fieldset className="stack">
          <legend>Deadline (optional)</legend>
          <p id={`${ids.dueDate}-hint`} className="field-hint">
            Leave the date empty for no deadline. Without a time, the deadline is 23:59 on that date.
          </p>
          <form.Field name="dueDate">
            {(field) => (
              <div className="field">
                <label htmlFor={ids.dueDate}>Deadline date</label>
                <input
                  id={ids.dueDate}
                  name="dueDate"
                  type="date"
                  value={field.state.value}
                  onChange={(event) => field.handleChange(event.target.value)}
                  onBlur={field.handleBlur}
                  aria-invalid={fieldErrors.dueDate ? true : undefined}
                  aria-describedby={describedBy('dueDate', `${ids.dueDate}-hint`)}
                />
                {fieldError('dueDate')}
              </div>
            )}
          </form.Field>
          <form.Field name="dueTime">
            {(field) => (
              <div className="field">
                <label htmlFor={ids.dueTime}>Deadline time</label>
                <input
                  id={ids.dueTime}
                  name="dueTime"
                  type="time"
                  value={field.state.value}
                  onChange={(event) => field.handleChange(event.target.value)}
                  onBlur={field.handleBlur}
                />
              </div>
            )}
          </form.Field>
          <form.Field name="dueTimeZone">
            {(field) => (
              <div className="field">
                <label htmlFor={ids.dueTimeZone}>Time zone</label>
                <select
                  id={ids.dueTimeZone}
                  name="dueTimeZone"
                  value={field.state.value}
                  onChange={(event) => field.handleChange(event.target.value)}
                  aria-invalid={fieldErrors.dueTimeZone ? true : undefined}
                  aria-describedby={describedBy('dueTimeZone')}
                >
                  {zoneOptions(field.state.value).map((zone) => (
                    <option key={zone} value={zone}>
                      {zoneLabel(zone)}
                    </option>
                  ))}
                </select>
                {fieldError('dueTimeZone')}
              </div>
            )}
          </form.Field>
        </fieldset>

        <div className="actions">
          <button type="submit" className="primary" aria-disabled={pending || undefined}>
            {editing ? 'Save changes' : 'Create task'}
          </button>
        </div>
      </form>
      <PastDeadlineDialog
        open={confirmingPast}
        closeLabel="Change deadline"
        onClose={() => setConfirmingPast(false)}
        onDecline={() => {
          setConfirmingPast(false);
          setFocusDeadline((n) => n + 1);
        }}
        onConfirm={() => {
          setConfirmingPast(false);
          void send(true);
        }}
      />
    </>
  );
}
