import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';

const FOCUSABLE =
  'a[href], area[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), iframe, [contenteditable="true"], [tabindex]:not([tabindex="-1"])';

function focusableIn(root: HTMLElement): HTMLElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE)).filter((el) => !el.closest('[inert]'));
}

export interface DialogProps {
  open: boolean;
  title: string;
  /** Called on Escape and by the caller's own Cancel/Close buttons. */
  onClose: () => void;
  children: ReactNode;
  /** Optional description shown under the title and referenced by aria-describedby. */
  description?: ReactNode;
  /** Control to focus on open; defaults to the first focusable control, else the heading. */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** `alertdialog` for confirmations that interrupt the workflow (e.g. a past deadline). */
  role?: 'dialog' | 'alertdialog';
}

/**
 * APG modal dialog: rendered in a portal, the rest of the page is made `inert`, Tab and
 * Shift+Tab cycle inside, Escape closes, and focus returns to the invoking control on close.
 */
export function Dialog({ open, title, onClose, children, description, initialFocusRef, role = 'dialog' }: DialogProps) {
  const titleId = useId();
  const descriptionId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const invoker = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;
    const host = panel?.closest<HTMLElement>('[data-dialog-host]');

    const siblings = Array.from(document.body.children).filter(
      (el): el is HTMLElement => el instanceof HTMLElement && el !== host && !el.hasAttribute('inert'),
    );
    siblings.forEach((el) => el.setAttribute('inert', ''));

    const target = initialFocusRef?.current ?? (panel ? focusableIn(panel)[0] : undefined) ?? headingRef.current;
    target?.focus();

    return () => {
      siblings.forEach((el) => el.removeAttribute('inert'));
      if (invoker?.isConnected) invoker.focus();
    };
    // Focus setup runs once per opening, not when the caller re-renders with a new ref.
  }, [open]);

  if (!open) return null;

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onCloseRef.current();
      return;
    }
    if (event.key !== 'Tab' || !panelRef.current) return;
    const items = focusableIn(panelRef.current);
    const first = items[0];
    const last = items.at(-1);
    if (!first || !last) {
      event.preventDefault();
      return;
    }
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return createPortal(
    <div className="dialog-backdrop" data-dialog-host="">
      <div
        ref={panelRef}
        className="dialog"
        role={role}
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId} ref={headingRef} tabIndex={-1} className="dialog-title">
          {title}
        </h2>
        {description ? (
          <div id={descriptionId} className="dialog-description">
            {description}
          </div>
        ) : null}
        {children}
      </div>
    </div>,
    document.body,
  );
}
