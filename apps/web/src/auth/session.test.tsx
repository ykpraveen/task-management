import { createMemoryHistory } from '@tanstack/react-router';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axe } from 'vitest-axe';
import { App } from '../app';
import { ASSIGNEE, CREATOR, createTestApp, type FakeAccount } from '../test/fakeAuth';
import {
  SIGN_IN_FAILED_MESSAGE,
  SIGN_IN_RATE_LIMITED_MESSAGE,
  SIGN_IN_UNAVAILABLE_MESSAGE,
  safeRedirectTarget,
} from './session';
import { createSupabase, loadRuntimeConfig } from './supabase';

async function renderApp(path: string, signedInAs: FakeAccount | null = null) {
  const history = createMemoryHistory({ initialEntries: [path] });
  const app = await createTestApp({ signedInAs, history });
  const view = render(<App queryClient={app.queryClient} router={app.router} />);
  await screen.findByRole('heading', { level: 1 });
  return { ...app, ...view, history };
}

async function signIn(email: string, password: string) {
  const user = userEvent.setup();
  await user.clear(screen.getByLabelText('Email address'));
  await user.type(screen.getByLabelText('Email address'), email);
  await user.type(screen.getByLabelText('Password'), password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  return user;
}

describe('safeRedirectTarget', () => {
  it.each([
    ['/tasks/42?tab=history#c1', '/tasks/42?tab=history#c1'],
    ['/', '/'],
  ])('keeps same-origin path %s', (raw, expected) => {
    expect(safeRedirectTarget(raw)).toBe(expected);
  });

  it.each([
    'https://evil.example/',
    '//evil.example/x',
    '/\\evil.example',
    'javascript:alert(1)',
    'tasks/1',
    '/sign-in',
    '/sign-in?redirect=/x',
    '/x\n',
    '',
    42,
    undefined,
    `/${'a'.repeat(3000)}`,
  ])('rejects %s', (raw) => {
    expect(safeRedirectTarget(raw)).toBe('/');
  });
});

describe('router guards', () => {
  it('sends signed-out users to sign-in and returns them to the validated target', async () => {
    const { router } = await renderApp('/?view=assigned');

    expect(screen.getByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/sign-in');
    expect(router.state.location.search).toEqual({ redirect: '/?view=assigned' });
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument();

    await signIn(CREATOR.email, CREATOR.password);

    await screen.findByRole('heading', { level: 1, name: 'My tasks' });
    expect(router.state.location.href).toBe('/?view=assigned');
    expect(screen.getByText('Signed in as Casey Creator')).toBeInTheDocument();
  });

  it('ignores an off-site redirect target', async () => {
    const { router } = await renderApp('/sign-in?redirect=https%3A%2F%2Fevil.example%2F');
    await signIn(CREATOR.email, CREATOR.password);
    await screen.findByRole('heading', { level: 1, name: 'My tasks' });
    expect(router.state.location.href).toBe('/');
  });

  it('skips the sign-in page for a signed-in user', async () => {
    const { router } = await renderApp('/sign-in', CREATOR);
    expect(screen.getByRole('heading', { level: 1, name: 'My tasks' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });
});

describe('sign-in form', () => {
  it('labels fields with autocomplete tokens and has no axe violations', async () => {
    const { container } = await renderApp('/sign-in');
    expect(screen.getByLabelText('Email address')).toHaveAttribute('autocomplete', 'username');
    expect(screen.getByLabelText('Email address')).toHaveAttribute('type', 'email');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    expect(document.title).toBe('Sign in – Tasks');
    expect(await axe(container)).toHaveNoViolations();
  });

  it('reports empty fields through a focused error summary', async () => {
    const user = userEvent.setup();
    const { container, fake } = await renderApp('/sign-in');

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByRole('group', { name: 'There is a problem' })).toHaveFocus();
    expect(screen.getByLabelText('Email address')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Email address')).toHaveAccessibleDescription('Enter your email address');
    expect(screen.getByLabelText('Password')).toHaveAccessibleDescription('Enter your password');
    expect(fake.auth.signInWithPassword).not.toHaveBeenCalled();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('uses one message for a wrong password and an unknown account', async () => {
    const { container } = await renderApp('/sign-in');

    await signIn(CREATOR.email, 'wrong-password');
    const summary = await screen.findByRole('group', { name: 'There is a problem' });
    expect(summary).toHaveTextContent(SIGN_IN_FAILED_MESSAGE);
    expect(summary).toHaveFocus();
    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(await axe(container)).toHaveNoViolations();

    await signIn('nobody@demo.invalid', 'whatever');
    await waitFor(() =>
      expect(screen.getByRole('group', { name: 'There is a problem' })).toHaveTextContent(SIGN_IN_FAILED_MESSAGE),
    );
  });

  it('distinguishes rate limiting and outages without revealing accounts', async () => {
    const { fake } = await renderApp('/sign-in');

    fake.state.mode = 'rate-limited';
    await signIn(CREATOR.email, CREATOR.password);
    expect(await screen.findByText(SIGN_IN_RATE_LIMITED_MESSAGE, { selector: 'a' })).toBeInTheDocument();

    fake.state.mode = 'unavailable';
    await signIn(CREATOR.email, CREATOR.password);
    expect(await screen.findByText(SIGN_IN_UNAVAILABLE_MESSAGE, { selector: 'a' })).toBeInTheDocument();
  });
});

describe('account teardown', () => {
  async function signedInWithState() {
    const app = await renderApp('/?status=blocked', CREATOR);
    app.queryClient.setQueryData(['tasks', CREATOR.id, 'list'], ['private task']);
    const cleaner = vi.fn();
    app.session.onAccountTeardown(cleaner);
    return { ...app, cleaner };
  }

  function expectTornDown(app: Awaited<ReturnType<typeof signedInWithState>>) {
    expect(app.queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(app.fake.client.removeAllChannels).toHaveBeenCalled();
    expect(app.cleaner).toHaveBeenCalledTimes(1);
  }

  it('clears cache, Realtime, account state and URL state on sign-out', async () => {
    const app = await signedInWithState();
    const cancel = vi.spyOn(app.queryClient, 'cancelQueries');
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    expect(cancel).toHaveBeenCalled();
    expectTornDown(app);
    expect(app.router.state.location.href).toBe('/sign-in');
    await waitFor(() => expect(screen.getByTestId('live-region')).toHaveTextContent('You are signed out.'));
    expect(window.localStorage.length).toBe(0);
  });

  it('tears down when Auth ends the session (refresh failure)', async () => {
    const app = await signedInWithState();
    act(() => app.fake.endSession());
    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    expectTornDown(app);
    expect(app.router.state.location.href).toBe('/sign-in');
    await waitFor(() =>
      expect(screen.getByTestId('live-region')).toHaveTextContent('Your session ended. Sign in again.'),
    );
  });

  it('tears down on a 401 from the API', async () => {
    const app = await signedInWithState();
    app.fetchImpl.mockResolvedValueOnce(new Response('{}', { status: 401 }));

    await act(() => app.api.tasks.GET('/users', { params: { query: { q: 'a' } } } as never));

    await screen.findByRole('heading', { level: 1, name: 'Sign in' });
    expectTornDown(app);
    expect(app.fake.auth.signOut).toHaveBeenCalledWith({ scope: 'local' });
    expect(app.session.accessToken()).toBeNull();
  });

  it('clears the previous account before another account renders', async () => {
    const app = await signedInWithState();
    await act(() => app.session.signIn(ASSIGNEE.email, ASSIGNEE.password));

    expectTornDown(app);
    expect(app.queryClient.getQueryData(['tasks', CREATOR.id, 'list'])).toBeUndefined();
    expect(app.session.currentUser()?.id).toBe(ASSIGNEE.id);
    expect(app.session.accessToken()).toBe(`token-${ASSIGNEE.id}`);
  });

  it('sends the current token on API requests', async () => {
    const app = await signedInWithState();
    await app.api.tasks.GET('/users', { params: { query: { q: 'a' } } } as never);
    const request = app.fetchImpl.mock.calls[0]![0];
    expect(request.headers.get('Authorization')).toBe(`Bearer token-${CREATOR.id}`);
  });
});

describe('Supabase client configuration', () => {
  it('keeps the session in sessionStorage, never localStorage', () => {
    const client = createSupabase({ supabasePublishableKey: 'sb_publishable_test' }, 'http://localhost');
    const auth = client.auth as unknown as { storage: Storage; autoRefreshToken: boolean; detectSessionInUrl: boolean };
    expect(auth.storage).toBe(window.sessionStorage);
    expect(auth.autoRefreshToken).toBe(true);
    expect(auth.detectSessionInUrl).toBe(false);
    client.auth.stopAutoRefresh();
  });

  it('loads the publishable key from /config.json', async () => {
    const fetchImpl = vi.fn(async () => Response.json({ supabasePublishableKey: 'sb_publishable_x' }));
    await expect(loadRuntimeConfig(fetchImpl as unknown as typeof fetch)).resolves.toEqual({
      supabasePublishableKey: 'sb_publishable_x',
    });
    expect(fetchImpl).toHaveBeenCalledWith('/config.json', expect.objectContaining({ cache: 'no-store' }));
  });
});
