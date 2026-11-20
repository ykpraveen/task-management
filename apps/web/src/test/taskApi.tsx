import type { Problem, Task, TaskApi, UserSummary } from '@task-management/api-client';
import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { LiveRegionProvider } from '../a11y/LiveRegion';
import { ASSIGNEE, CREATOR, createTestApp, type FakeAccount } from './fakeAuth';

export type DeadlineRequest = TaskApi.components['schemas']['DeadlineRequest'];

/** Above Number.MAX_SAFE_INTEGER, so any numeric conversion on the way would change it. */
export const TASK_ID = '9007199254740993';
export const REQUEST_ID = '9007199254740995';

export const CREATOR_SUMMARY: UserSummary = { id: CREATOR.id, displayName: 'Casey Creator', email: CREATOR.email };
export const ASSIGNEE_SUMMARY: UserSummary = { id: ASSIGNEE.id, displayName: 'Avery Assignee', email: ASSIGNEE.email };
export const OTHER_SUMMARY: UserSummary = {
  id: '00000000-0000-4000-8000-000000000003',
  displayName: 'Avery Other',
  email: 'other@demo.invalid',
};
export const DIRECTORY = [CREATOR_SUMMARY, ASSIGNEE_SUMMARY, OTHER_SUMMARY];

const NO_PERMISSIONS: Task['permissions'] = {
  edit: false,
  changeStatus: false,
  reopen: false,
  cancel: false,
  comment: false,
  upload: false,
  requestDeadline: false,
  decideDeadlineRequest: false,
};

export const CREATOR_PERMISSIONS: Task['permissions'] = {
  ...NO_PERMISSIONS,
  edit: true,
  changeStatus: true,
  cancel: true,
  comment: true,
  upload: true,
  decideDeadlineRequest: true,
};

export const ASSIGNEE_PERMISSIONS: Task['permissions'] = {
  ...NO_PERMISSIONS,
  changeStatus: true,
  comment: true,
  upload: true,
  requestDeadline: true,
};

export function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: TASK_ID,
    creator: CREATOR_SUMMARY,
    assignee: ASSIGNEE_SUMMARY,
    title: 'Prepare quarterly report',
    description: 'Collect the numbers from finance.',
    priority: 'normal',
    status: 'todo',
    dueAt: '2026-12-04T22:59:00Z',
    dueTimeZone: 'Europe/Berlin',
    blockedReason: null,
    cancellationReason: null,
    createdAt: '2026-11-01T09:00:00Z',
    updatedAt: '2026-11-01T09:00:00Z',
    completedAt: null,
    isOverdue: false,
    version: '7',
    permissions: CREATOR_PERMISSIONS,
    ...overrides,
  };
}

export function makeDeadlineRequest(overrides: Partial<DeadlineRequest> = {}): DeadlineRequest {
  return {
    id: REQUEST_ID,
    taskId: TASK_ID,
    requester: ASSIGNEE_SUMMARY,
    proposedDueAt: '2026-12-11T22:59:00Z',
    proposedTimeZone: 'Europe/Berlin',
    reason: 'Finance closes the books a week later',
    state: 'pending',
    createdAt: '2026-11-10T09:00:00Z',
    resolvedAt: null,
    resolvedBy: null,
    version: '1',
    ...overrides,
  };
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

/** A task response with the ETag the server sends alongside it. */
export function taskResponse(task: Task, status = 200): Response {
  return json(task, status, { ETag: `"${task.version}"` });
}

export function problem(status: number, code: Problem['code'], extra: Partial<Problem> = {}): Response {
  const body: Problem = {
    type: `https://tasks.example/problems/${code.toLowerCase()}`,
    title: code,
    status,
    code,
    detail: `Request failed with ${code}`,
    requestId: 'req-test',
    ...extra,
  };
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/problem+json' } });
}

export function emptyPage(): Response {
  return json({ items: [], nextCursor: null });
}

/** A response the test releases later, to observe the UI while a request is in flight. */
export function deferred() {
  let resolve!: (response: Response) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<Response>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

export interface RecordedRequest {
  method: string;
  /** Path below /api/v1, e.g. `/tasks/9007199254740993/status`. */
  path: string;
  query: URLSearchParams;
  headers: Headers;
  /** Parsed JSON body, or undefined for an empty body. */
  body: unknown;
}

type Handler = (request: RecordedRequest) => Response | Promise<Response>;

/**
 * In-memory task API behind the app's real generated client: it replaces the `fetch` that
 * createTestApp hands to createApiClients, so requests carry the real headers and bodies.
 * Routes are keyed `METHOD /path`. Unknown routes answer 404 problem+json, list reads of a
 * task's comments, history and attachments answer an empty page, and user search filters
 * DIRECTORY.
 */
export function createFakeTaskApi(fetchImpl: { mockImplementation(fn: (request: Request) => Promise<Response>): unknown }) {
  const persistent = new Map<string, Handler>();
  const queued = new Map<string, Handler[]>();
  const requests: RecordedRequest[] = [];

  persistent.set('GET /users', ({ query }) => {
    const q = (query.get('query') ?? '').trim().toLowerCase();
    const items = DIRECTORY.filter((u) => u.displayName.toLowerCase().includes(q) || u.email.includes(q));
    return json({ items, nextCursor: null });
  });
  for (const list of ['comments', 'history', 'attachments', 'deadline-requests']) {
    persistent.set(`GET /tasks/${TASK_ID}/${list}`, emptyPage);
  }

  fetchImpl.mockImplementation(async (request: Request) => {
    const url = new URL(request.url);
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const text = request.method === 'GET' ? '' : await request.clone().text();
    const recorded: RecordedRequest = {
      method: request.method,
      path,
      query: url.searchParams,
      headers: request.headers,
      body: text ? JSON.parse(text) : undefined,
    };
    requests.push(recorded);
    const key = `${request.method} ${path}`;
    const handler = queued.get(key)?.shift() ?? persistent.get(key);
    return handler ? handler(recorded) : problem(404, 'NOT_FOUND');
  });

  return {
    requests,
    /** Answers every matching request until replaced. */
    on(key: string, handler: Handler) {
      persistent.set(key, handler);
    },
    /** Answers only the next matching request; takes precedence over `on`. */
    once(key: string, handler: Handler) {
      queued.set(key, [...(queued.get(key) ?? []), handler]);
    },
    /** Requests made to one route, oldest first. */
    calls(key: string): RecordedRequest[] {
      return requests.filter((r) => `${r.method} ${r.path}` === key);
    },
    /** Every non-GET request, oldest first. */
    mutations(): RecordedRequest[] {
      return requests.filter((r) => r.method !== 'GET');
    },
  };
}

export type FakeTaskApi = ReturnType<typeof createFakeTaskApi>;

/**
 * A signed-in app context (real SessionController, Query client and generated API client)
 * with the fake task API installed. `render` mounts a component inside the same providers
 * the app uses, without the router.
 */
export async function createTaskTestApp(account: FakeAccount) {
  const app = await createTestApp({ signedInAs: account });
  const server = createFakeTaskApi(app.fetchImpl);
  return {
    ...app,
    server,
    render(ui: ReactElement) {
      return render(
        <QueryClientProvider client={app.queryClient}>
          <LiveRegionProvider>{ui}</LiveRegionProvider>
        </QueryClientProvider>,
      );
    },
  };
}

export function liveRegion(): HTMLElement {
  return screen.getByTestId('live-region');
}

/** Text-query option: match visible page text, not the copy in the polite live region. */
export const OUTSIDE_LIVE_REGION = { ignore: '[data-testid="live-region"], script, style' };

export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
