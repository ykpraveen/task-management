import { isAuthApiError, type Session } from '@supabase/supabase-js';
import type { QueryClient } from '@tanstack/react-query';
import { redirect, type ParsedLocation } from '@tanstack/react-router';
import { useSyncExternalStore } from 'react';
import type { AuthClient } from './supabase';

export const SIGN_IN_PATH = '/sign-in';

export interface CurrentUser {
  id: string;
  email: string;
  displayName: string | null;
  timeZone: string | null;
}

export type TeardownReason = 'sign-out' | 'account-switch' | 'session-ended' | 'unauthenticated';

export type SignInResult = { ok: true } | { ok: false; message: string };

export type ProfileUpdateResult = { ok: true } | { ok: false; message: string };

export const PROFILE_SAVE_FAILED_MESSAGE = 'Your profile was not saved. Try again.';

/**
 * Sign-in failures never reveal whether an account exists, is unconfirmed or is deactivated:
 * every credential-type rejection maps to the same message.
 */
export const SIGN_IN_FAILED_MESSAGE = 'Sign-in failed. Check your email address and password and try again.';
export const SIGN_IN_RATE_LIMITED_MESSAGE = 'Too many sign-in attempts. Wait a minute and try again.';
export const SIGN_IN_UNAVAILABLE_MESSAGE = 'Sign-in is unavailable right now. Try again later.';

function toUser(session: Session | null): CurrentUser | null {
  if (!session) return null;
  const metadata = (session.user.user_metadata ?? {}) as Record<string, unknown>;
  return {
    id: session.user.id,
    email: session.user.email ?? '',
    displayName: typeof metadata.display_name === 'string' ? metadata.display_name : null,
    timeZone: typeof metadata.time_zone === 'string' ? metadata.time_zone : null,
  };
}

/**
 * Owns the browser session (FR-030). When the signed-in account ends or changes — sign-out,
 * account switch, failed refresh or a 401 — it tears down everything account-scoped before
 * the next account renders: in-flight queries are cancelled, the Query cache is cleared,
 * Realtime channels are removed and registered account-state cleaners (search text, cursors)
 * run. Teardown listeners then move the router to sign-in without the old URL state.
 */
export class SessionController {
  private session: Session | null = null;
  private user: CurrentUser | null = null;
  private readonly listeners = new Set<() => void>();
  private readonly teardownListeners = new Set<(reason: TeardownReason) => void>();
  private readonly accountCleaners = new Set<() => void>();
  private unsubscribeAuth: (() => void) | null = null;
  private pendingReason: TeardownReason | null = null;

  constructor(
    private readonly client: AuthClient,
    private readonly queryClient: QueryClient,
  ) {}

  /** Restores the tab's session (if any) and follows Auth events. Call once before rendering. */
  async init(): Promise<void> {
    const { data } = await this.client.auth.getSession();
    this.apply(data.session);
    const { data: listener } = this.client.auth.onAuthStateChange((_event, session) => {
      // Do not await Supabase calls inside this callback (Auth holds its lock here).
      this.apply(session);
    });
    this.unsubscribeAuth = () => listener.subscription.unsubscribe();
  }

  dispose(): void {
    this.unsubscribeAuth?.();
    this.unsubscribeAuth = null;
  }

  currentUser(): CurrentUser | null {
    return this.user;
  }

  isSignedIn(): boolean {
    return this.user !== null;
  }

  /** For the API client's Authorization header; read on every request. */
  accessToken(): string | null {
    return this.session?.access_token ?? null;
  }

  async signIn(email: string, password: string): Promise<SignInResult> {
    try {
      const { data, error } = await this.client.auth.signInWithPassword({ email, password });
      if (error) {
        if (isAuthApiError(error) && error.status === 429) {
          return { ok: false, message: SIGN_IN_RATE_LIMITED_MESSAGE };
        }
        if (isAuthApiError(error) && error.status < 500) {
          return { ok: false, message: SIGN_IN_FAILED_MESSAGE };
        }
        return { ok: false, message: SIGN_IN_UNAVAILABLE_MESSAGE };
      }
      if (this.user && data.session && this.user.id !== data.session.user.id) {
        this.pendingReason = 'account-switch';
      }
      this.apply(data.session);
      return { ok: true };
    } catch {
      return { ok: false, message: SIGN_IN_UNAVAILABLE_MESSAGE };
    }
  }

  async signOut(): Promise<void> {
    this.pendingReason = 'sign-out';
    try {
      // Revokes this session's refresh token; local state is removed even if the call fails.
      await this.client.auth.signOut({ scope: 'local' });
    } finally {
      this.apply(null);
    }
  }

  /**
   * Saves display name and time zone through Supabase Auth's own user-update API; the
   * identity directory picks them up from user metadata in the same transaction.
   */
  async updateProfile(profile: { displayName: string; timeZone: string }): Promise<ProfileUpdateResult> {
    try {
      const { data, error } = await this.client.auth.updateUser({
        data: { display_name: profile.displayName, time_zone: profile.timeZone },
      });
      if (error) {
        if (isAuthApiError(error) && error.status === 401) {
          this.handleUnauthenticated();
        }
        return { ok: false, message: PROFILE_SAVE_FAILED_MESSAGE };
      }
      if (this.session && data.user) {
        this.apply({ ...this.session, user: data.user });
      }
      return { ok: true };
    } catch {
      return { ok: false, message: PROFILE_SAVE_FAILED_MESSAGE };
    }
  }

  /** Called by the API client on any 401. */
  handleUnauthenticated(): void {
    if (!this.user) return;
    this.pendingReason = 'unauthenticated';
    this.apply(null);
    void this.client.auth.signOut({ scope: 'local' }).catch(() => undefined);
  }

  /** Account-scoped state outside the Query cache (search text, cursors). Returns an unregister function. */
  onAccountTeardown(cleaner: () => void): () => void {
    this.accountCleaners.add(cleaner);
    return () => this.accountCleaners.delete(cleaner);
  }

  onTeardown(listener: (reason: TeardownReason) => void): () => void {
    this.teardownListeners.add(listener);
    return () => this.teardownListeners.delete(listener);
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): CurrentUser | null => this.user;

  private apply(session: Session | null): void {
    const previous = this.user;
    const next = toUser(session);
    const reason = this.pendingReason;
    this.pendingReason = null;

    if (previous && previous.id !== next?.id) {
      this.teardown(reason ?? (next ? 'account-switch' : 'session-ended'));
    }

    this.session = session;
    const changed =
      previous?.id !== next?.id ||
      previous?.email !== next?.email ||
      previous?.displayName !== next?.displayName ||
      previous?.timeZone !== next?.timeZone;
    if (changed) {
      this.user = next;
      this.listeners.forEach((listener) => listener());
    }
  }

  private teardown(reason: TeardownReason): void {
    void this.queryClient.cancelQueries();
    this.queryClient.clear();
    void this.client.removeAllChannels();
    this.accountCleaners.forEach((cleaner) => cleaner());
    this.teardownListeners.forEach((listener) => listener(reason));
  }
}

export function useCurrentUser(session: SessionController): CurrentUser | null {
  return useSyncExternalStore(session.subscribe, session.getSnapshot);
}

/**
 * Returns a same-origin, path-relative target for the post-sign-in redirect, or `/`.
 * Rejects absolute and protocol-relative URLs, backslash tricks, control characters and the
 * sign-in page itself, so the parameter cannot become an open redirect.
 */
export function safeRedirectTarget(raw: unknown): string {
  if (typeof raw !== 'string' || raw === '' || raw.length > 2048) return '/';
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return '/';
  if (/[\u0000-\u001f\u007f]/.test(raw)) return '/';
  const base = 'https://app.invalid';
  let url: URL;
  try {
    url = new URL(raw, base);
  } catch {
    return '/';
  }
  if (url.origin !== base) return '/';
  if (url.pathname === SIGN_IN_PATH || url.pathname.startsWith(`${SIGN_IN_PATH}/`)) return '/';
  return `${url.pathname}${url.search}${url.hash}`;
}

/** Router `beforeLoad` guard for signed-in pages. */
export function requireSession({
  context,
  location,
}: {
  context: { session: SessionController };
  location: ParsedLocation;
}): void {
  if (!context.session.isSignedIn()) {
    throw redirect({ to: SIGN_IN_PATH, search: { redirect: location.href } });
  }
}
