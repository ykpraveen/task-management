import { useId } from 'react';
import { Dialog } from '../../a11y/Dialog';
import { problemCode } from './api';

export const CONFLICT_MESSAGE = 'This task changed since you opened it';

/**
 * Text for a mutation that did not happen. `notDone` says what failed ("The task was not
 * created."); codes the user can act on get a specific hint, everything else a retry hint.
 * Problem details from the server are never shown verbatim.
 */
export function failureMessage(error: unknown, notDone: string): string {
  switch (problemCode(error)) {
    case 'NOT_FOUND':
      return `${notDone} The task is no longer available to you.`;
    case 'FORBIDDEN':
      return `${notDone} You are not allowed to make this change.`;
    case 'RATE_LIMITED':
      return `${notDone} Too many requests. Wait a moment and try again.`;
    case 'IDEMPOTENCY_CONFLICT':
    case 'OPERATION_IN_PROGRESS':
      return `${notDone} Another attempt is still being processed. Reload and check before trying again.`;
    default:
      return `${notDone} Try again.`;
  }
}

/**
 * Persistent, focusable notice for a 409 STALE_VERSION (contracts/ui-accessibility.md). The
 * caller announces CONFLICT_MESSAGE politely; focus is not moved here.
 */
export function ConflictNotice({ onReload, detail }: { onReload: () => void; detail: string }) {
  const titleId = useId();
  return (
    <div className="notice conflict-notice" role="region" aria-labelledby={titleId} tabIndex={-1}>
      <p id={titleId}>
        <strong>{CONFLICT_MESSAGE}</strong>
      </p>
      <p>{detail}</p>
      <div className="actions">
        <button type="button" onClick={onReload}>
          Reload
        </button>
      </div>
    </div>
  );
}

export interface PastDeadlineDialogProps {
  open: boolean;
  /** Resend the same request with confirmPastDeadline: true. */
  onConfirm: () => void;
  /** Escape: close without sending. */
  onClose: () => void;
  closeLabel: string;
  /** The second button; defaults to onClose. */
  onDecline?: () => void;
}

/** Explicit confirmation for PAST_DEADLINE_CONFIRMATION_REQUIRED; never an automatic retry. */
export function PastDeadlineDialog({ open, onConfirm, onClose, closeLabel, onDecline }: PastDeadlineDialogProps) {
  return (
    <Dialog
      open={open}
      role="alertdialog"
      title="Deadline is in the past"
      description="The deadline has already passed, so the task will show as overdue straight away."
      onClose={onClose}
    >
      <div className="actions">
        <button type="button" className="primary" onClick={onConfirm}>
          Keep past deadline
        </button>
        <button type="button" onClick={onDecline ?? onClose}>
          {closeLabel}
        </button>
      </div>
    </Dialog>
  );
}
