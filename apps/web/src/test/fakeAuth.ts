import { AuthApiError, AuthRetryableFetchError, type AuthChangeEvent, type Session, type User } from '@supabase/supabase-js';
import { createApiClients } from '@task-management/api-client';
import type { RouterHistory } from '@tanstack/react-router';
import { vi } from 'vitest';
import { createAppRouter, createQueryClient } from '../app';
import { SessionController } from '../auth/session';
import type { AuthClient } from '../auth/supabase';

export interface FakeAccount {
  id: string;
  email: string;
  password: string;
  metadata?: Record<string, unknown>;
}

export const CREATOR: FakeAccount = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'creator@demo.invalid',
  password: 'creator-password',
  metadata: { display_name: 'Casey Creator', time_zone: 'Europe/Berlin' },
};

export const ASSIGNEE: FakeAccount = {
  id: '00000000-0000-4000-8000-000000000002',
  email: 'assignee@demo.invalid',
  password: 'assignee-password',
  metadata: { display_name: 'Avery Assignee', time_zone: 'America/New_York' },
};

function makeSession(account: FakeAccount, metadata = account.metadata ?? {}): Session {
  const user = {
    id: account.id,
    email: account.email,
    user_metadata: { ...metadata },
    app_metadata: {},
    aud: 'authenticated',
    created_at: '2026-01-01T00:00:00Z',
  } as User;
  return {
    access_token: `token-${account.id}`,
    refresh_token: `refresh-${account.id}`,
    expires_in: 3600,
    token_type: 'bearer',
    user,
  } as Session;
}

export type FakeAuthMode = 'normal' | 'rate-limited' | 'unavailable';

/** In-memory stand-in for the Supabase Auth client with the behaviors the app depends on. */
export function createFakeAuth(accounts: FakeAccount[] = [CREATOR, ASSIGNEE], initial: FakeAccount | null = null) {
  let session: Session | null = initial ? makeSession(initial) : null;
  const listeners = new Set<(event: AuthChangeEvent, session: Session | null) => void>();
  const state = { mode: 'normal' as FakeAuthMode, updateError: null as string | null };
  const emit = (event: AuthChangeEvent) => listeners.forEach((l) => l(event, session));

  const auth = {
    getSession: vi.fn(async () => ({ data: { session }, error: null })),
    onAuthStateChange: vi.fn((callback: (event: AuthChangeEvent, session: Session | null) => void) => {
      listeners.add(callback);
      return { data: { subscription: { id: 'fake', callback, unsubscribe: () => listeners.delete(callback) } } };
    }),
    signInWithPassword: vi.fn(async ({ email, password }: { email: string; password: string }) => {
      if (state.mode === 'rate-limited') {
        return { data: { user: null, session: null }, error: new AuthApiError('rate limited', 429, 'over_request_rate_limit') };
      }
      if (state.mode === 'unavailable') {
        return { data: { user: null, session: null }, error: new AuthRetryableFetchError('fetch failed', 0) };
      }
      const account = accounts.find((a) => a.email === email && a.password === password);
      if (!account) {
        return { data: { user: null, session: null }, error: new AuthApiError('Invalid login credentials', 400, 'invalid_credentials') };
      }
      session = makeSession(account);
      emit('SIGNED_IN');
      return { data: { user: session.user, session }, error: null };
    }),
    signOut: vi.fn(async () => {
      session = null;
      emit('SIGNED_OUT');
      return { error: null };
    }),
    updateUser: vi.fn(async ({ data }: { data?: Record<string, unknown> }) => {
      if (!session) {
        return { data: { user: null }, error: new AuthApiError('not signed in', 401, 'no_authorization') };
      }
      if (state.updateError) {
        return { data: { user: null }, error: new AuthApiError(state.updateError, 422, 'validation_failed') };
      }
      const { id, email = '' } = session.user;
      session = makeSession({ id, email, password: '' }, { ...session.user.user_metadata, ...data });
      emit('USER_UPDATED');
      return { data: { user: session.user }, error: null };
    }),
  };

  const client = {
    auth,
    removeAllChannels: vi.fn(async () => []),
  } as unknown as AuthClient;

  return {
    client,
    auth,
    state,
    /** Simulates Auth ending the session itself, e.g. a failed token refresh. */
    endSession() {
      session = null;
      emit('SIGNED_OUT');
    },
  };
}

/** Builds a router with a real SessionController over the fake Auth client. */
export async function createTestApp(options: { signedInAs?: FakeAccount | null; history?: RouterHistory } = {}) {
  const fake = createFakeAuth(undefined, options.signedInAs ?? null);
  const queryClient = createQueryClient();
  const session = new SessionController(fake.client, queryClient);
  await session.init();
  const fetchImpl = vi.fn(async (_request: Request) => new Response('{}', { status: 200 }));
  const api = createApiClients({
    baseUrl: 'http://localhost/api/v1',
    getAccessToken: () => session.accessToken(),
    onUnauthenticated: () => session.handleUnauthenticated(),
    fetch: fetchImpl,
  });
  const router = createAppRouter({ queryClient, session, api, ...(options.history ? { history: options.history } : {}) });
  return { fake, queryClient, session, api, router, fetchImpl };
}
