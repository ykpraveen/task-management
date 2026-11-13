import type { Priority, TaskStatus } from '@task-management/api-client';

export const STATUS_LABELS: Record<TaskStatus, string> = {
  todo: 'To do',
  in_progress: 'In progress',
  blocked: 'Blocked',
  done: 'Done',
  cancelled: 'Cancelled',
};

export const PRIORITY_LABELS: Record<Priority, string> = {
  low: 'Low',
  normal: 'Normal',
  high: 'High',
};

/**
 * State always appears as text (FR-031): color classes are supplementary styling only.
 */
export function StatusText({ status }: { status: TaskStatus }) {
  return <span className={`status-text status-${status}`}>{STATUS_LABELS[status]}</span>;
}

export function PriorityText({ priority }: { priority: Priority }) {
  return <span className={`priority-text priority-${priority}`}>{PRIORITY_LABELS[priority]} priority</span>;
}

/** Renders nothing for tasks that are not overdue, so lists do not repeat "Not overdue". */
export function OverdueText({ overdue }: { overdue: boolean }) {
  return overdue ? <span className="overdue-text">Overdue</span> : null;
}

export function UnreadText({ unread }: { unread: boolean }) {
  return <span className={unread ? 'unread-text' : 'read-text'}>{unread ? 'Unread' : 'Read'}</span>;
}
