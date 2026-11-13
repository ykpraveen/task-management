import type { ApiClients } from '@task-management/api-client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  Link,
  Outlet,
  RouterProvider,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  redirect,
  useRouteContext,
  useRouter,
  type RouterHistory,
} from '@tanstack/react-router';
import { useEffect } from 'react';
import { LiveRegionProvider, useAnnouncer } from './a11y/LiveRegion';
import { RouteFocus } from './a11y/RouteFocus';
import { ProfileSettings, TimeZoneOffer } from './auth/ProfileSettings';
import { SignIn } from './auth/SignIn';
import { SIGN_IN_PATH, requireSession, safeRedirectTarget, useCurrentUser, type SessionController } from './auth/session';

export interface RouterContext {
  queryClient: QueryClient;
  session: SessionController;
  api: ApiClients;
}

/**
 * Memory-only server cache (no persistence, no offline mutation queue). Active queries poll
 * every 10 seconds and refetch on focus and reconnect; mutations are never retried
 * automatically, so an ambiguous mutation is not replayed behind the user's back.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: 10_000,
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
        retry: 1,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}

/**
 * After any account teardown (sign-out, switch, failed refresh, 401) go to sign-in with a
 * replaced history entry and no search, so the previous account's URL state is not reused.
 */
function SessionTeardownRedirect() {
  const router = useRouter();
  const { session } = useRouteContext({ from: '__root__' });
  const { announce } = useAnnouncer();
  useEffect(
    () =>
      session.onTeardown((reason) => {
        void router.navigate({ to: SIGN_IN_PATH, search: {}, replace: true });
        if (reason === 'sign-out') announce('You are signed out.');
        else if (reason !== 'account-switch') announce('Your session ended. Sign in again.');
      }),
    [router, session, announce],
  );
  return null;
}

function RootLayout() {
  const { session } = useRouteContext({ from: '__root__' });
  const user = useCurrentUser(session);
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="app-header">
        <p className="app-name">Tasks</p>
        {user ? (
          <>
            <nav aria-label="Main">
              <ul>
                <li>
                  <Link to="/" activeProps={{ 'aria-current': 'page' }} activeOptions={{ exact: true }}>
                    My tasks
                  </Link>
                </li>
                <li>
                  <Link to="/profile" activeProps={{ 'aria-current': 'page' }}>
                    Profile
                  </Link>
                </li>
              </ul>
            </nav>
            <div className="account">
              <span className="account-name">Signed in as {user.displayName ?? user.email}</span>
              <button type="button" onClick={() => void session.signOut()}>
                Sign out
              </button>
            </div>
          </>
        ) : null}
      </header>
      <main id="main" tabIndex={-1}>
        <TimeZoneOffer session={session} />
        <Outlet />
      </main>
      <RouteFocus />
      <SessionTeardownRedirect />
    </>
  );
}

function SignInPage() {
  const { session } = signInRoute.useRouteContext();
  const search = signInRoute.useSearch();
  const router = useRouter();
  return (
    <SignIn
      session={session}
      onSignedIn={() => void router.navigate({ href: safeRedirectTarget(search.redirect), replace: true })}
    />
  );
}

function HomePage() {
  return <h1>My tasks</h1>;
}

function NotFoundPage() {
  return (
    <>
      <h1>Page not found</h1>
      <p>
        The page does not exist or you do not have access to it. <Link to="/">Go to my tasks</Link>
      </p>
    </>
  );
}

const rootRoute = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
  notFoundComponent: NotFoundPage,
  staticData: { title: undefined },
});

const signInRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: SIGN_IN_PATH,
  // Only the validated return target is accepted; credentials never enter the URL.
  validateSearch: (search: Record<string, unknown>): { redirect?: string } =>
    typeof search.redirect === 'string' ? { redirect: safeRedirectTarget(search.redirect) } : {},
  beforeLoad: ({ context, search }) => {
    if (context.session.isSignedIn()) {
      throw redirect({ href: safeRedirectTarget(search.redirect), replace: true });
    }
  },
  component: SignInPage,
  staticData: { title: 'Sign in' },
});

/** Layout route for every signed-in page. */
const authenticatedRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'authenticated',
  beforeLoad: requireSession,
});

const homeRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/',
  component: HomePage,
  staticData: { title: 'My tasks' },
});

function ProfilePage() {
  const { session } = profileRoute.useRouteContext();
  return <ProfileSettings session={session} />;
}

const profileRoute = createRoute({
  getParentRoute: () => authenticatedRoute,
  path: '/profile',
  component: ProfilePage,
  staticData: { title: 'Profile settings' },
});

const routeTree = rootRoute.addChildren([signInRoute, authenticatedRoute.addChildren([homeRoute, profileRoute])]);

export interface AppRouterOptions extends RouterContext {
  history?: RouterHistory;
}

export function createAppRouter({ history, ...context }: AppRouterOptions) {
  return createRouter({
    routeTree,
    context,
    ...(history ? { history } : {}),
    defaultNotFoundComponent: NotFoundPage,
    defaultPreload: false,
  });
}

export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
  interface StaticDataRouteOption {
    /** Page title for document.title; omitted when a page sets its own with useDocumentTitle. */
    title?: string | undefined;
  }
}

export function App({ queryClient, router }: { queryClient: QueryClient; router: AppRouter }) {
  return (
    <QueryClientProvider client={queryClient}>
      <LiveRegionProvider>
        <RouterProvider router={router} />
      </LiveRegionProvider>
    </QueryClientProvider>
  );
}
