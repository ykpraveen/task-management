import createClient, { type Middleware } from 'openapi-fetch';
import type { paths as NotificationPaths } from './generated/notifications';
import type { paths as TaskPaths } from './generated/task-api';

export interface ApiClientOptions {
  /** Same-origin API root; defaults to `/api/v1`. */
  baseUrl?: string;
  /** Current Supabase access token, or null when signed out. Read on every request. */
  getAccessToken: () => string | null | Promise<string | null>;
  /** Called on any 401 so the app can tear down the session (FR-030). */
  onUnauthenticated?: () => void;
  fetch?: (input: Request) => Promise<Response>;
}

/**
 * Typed clients for the task and notification APIs. Every operation accepts
 * `{ signal }`, so TanStack Query's AbortSignal cancels stale reads; cancelling a
 * request never undoes a mutation the server already committed.
 */
export function createApiClients(options: ApiClientOptions) {
  const baseUrl = options.baseUrl ?? '/api/v1';
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = await options.getAccessToken();
      if (token) {
        request.headers.set('Authorization', `Bearer ${token}`);
      }
      return request;
    },
    onResponse({ response }) {
      if (response.status === 401) {
        options.onUnauthenticated?.();
      }
      return response;
    },
  };
  const common = { baseUrl, ...(options.fetch ? { fetch: options.fetch } : {}) };
  const tasks = createClient<TaskPaths>(common);
  const notifications = createClient<NotificationPaths>(common);
  tasks.use(auth);
  notifications.use(auth);
  return { tasks, notifications };
}

export type ApiClients = ReturnType<typeof createApiClients>;

/** A fresh Idempotency-Key. Keep it with the pending mutation and reuse it on retry. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
