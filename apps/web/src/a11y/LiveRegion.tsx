import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

/** Notification-count changes are announced at most once per this interval (contracts/ui-accessibility.md). */
export const NOTIFICATION_COUNT_INTERVAL_MS = 10_000;

export type AnnouncementKind = 'outcome' | 'notification-count';

export interface Announcer {
  /**
   * Queues a polite message. `outcome` messages (the user's own saved, failed, posted…) are
   * announced at once; `notification-count` messages are rate-limited and only the latest one
   * pending in a window is spoken.
   */
  announce(message: string, kind?: AnnouncementKind): void;
}

const AnnouncerContext = createContext<Announcer | null>(null);

export function useAnnouncer(): Announcer {
  const announcer = useContext(AnnouncerContext);
  if (!announcer) {
    throw new Error('useAnnouncer must be used inside <LiveRegionProvider>');
  }
  return announcer;
}

/**
 * The app's single polite live region. Rendering one region (instead of one per component)
 * keeps screen readers from queueing duplicate or competing messages. Background refreshes
 * must not call `announce`.
 */
export function LiveRegionProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState('');
  const lastCountAt = useRef<number | null>(null);
  const pendingCount = useRef<string | null>(null);
  const countTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const speak = useCallback((text: string) => {
    // Clear first so repeating the same text is announced again.
    setMessage('');
    if (clearTimer.current) clearTimeout(clearTimer.current);
    clearTimer.current = setTimeout(() => setMessage(text), 50);
  }, []);

  const flushCount = useCallback(() => {
    countTimer.current = null;
    if (pendingCount.current !== null) {
      lastCountAt.current = Date.now();
      speak(pendingCount.current);
      pendingCount.current = null;
    }
  }, [speak]);

  const announce = useCallback(
    (text: string, kind: AnnouncementKind = 'outcome') => {
      if (!text) return;
      if (kind === 'outcome') {
        speak(text);
        return;
      }
      const now = Date.now();
      const elapsed = lastCountAt.current === null ? Infinity : now - lastCountAt.current;
      if (elapsed >= NOTIFICATION_COUNT_INTERVAL_MS && !countTimer.current) {
        lastCountAt.current = now;
        speak(text);
        return;
      }
      pendingCount.current = text;
      if (!countTimer.current) {
        countTimer.current = setTimeout(flushCount, NOTIFICATION_COUNT_INTERVAL_MS - elapsed);
      }
    },
    [speak, flushCount],
  );

  useEffect(
    () => () => {
      if (countTimer.current) clearTimeout(countTimer.current);
      if (clearTimer.current) clearTimeout(clearTimer.current);
    },
    [],
  );

  const value = useMemo<Announcer>(() => ({ announce }), [announce]);

  return (
    <AnnouncerContext.Provider value={value}>
      {children}
      <div className="visually-hidden" role="status" aria-live="polite" aria-atomic="true" data-testid="live-region">
        {message}
      </div>
    </AnnouncerContext.Provider>
  );
}
