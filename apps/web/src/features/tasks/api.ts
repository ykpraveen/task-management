import {
  ifMatch,
  newIdempotencyKey,
  type ApiClients,
  type Problem,
  type Task,
  type TaskApi,
  type UserSummary,
} from '@task-management/api-client';
import { useInfiniteQuery, useQuery, type QueryClient } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';
import { useCurrentUser, type SessionController } from '../../auth/session';

export type TaskView = TaskApi.components['schemas']['TaskView'];
export type HistoryEntry = TaskApi.components['schemas']['HistoryEntry'];
export type DeadlineRequest = TaskApi.components['schemas']['DeadlineRequest'];

export const PAGE_SIZE = 50;
/** Retained pages per list; Load more stops offering further pages past this bound. */
export const MAX_PAGES = 10;

/**
 * A failed API call. `problem` is the problem+json body when the server sent one; a network
 * failure is not an ApiError (fetch rejects with TypeError instead).
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | null,
  ) {
    super(problem?.detail ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
  }
}

export function problemCode(error: unknown): Problem['code'] | undefined {
  return error instanceof ApiError ? error.problem?.code : undefined;
}

function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && 'code' in body && 'status' in body;
}

/** Returns the success body of an openapi-fetch result or throws an ApiError. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.response.ok && result.error === undefined) {
    return result.data as T;
  }
  throw new ApiError(result.response.status, isProblem(result.error) ? result.error : null);
}

/** Rejects a 2xx body that is not a page, so a malformed response becomes a failed read. */
function page<T extends { items: unknown[] }>(body: T): T {
  if (!Array.isArray((body as { items?: unknown } | null)?.items)) {
    throw new Error('Malformed page response');
  }
  return body;
}

/**
 * Query keys are scoped by the signed-in user's ID, so a late response for a previous account
 * can never fill the next account's cache. The detail key prefixes the task's own lists so
 * targeted invalidation can pick them individually.
 */
export const taskKeys = {
  all: (userId: string) => ['user', userId, 'tasks'] as const,
  lists: (userId: string) => [...taskKeys.all(userId), 'list'] as const,
  list: (userId: string, view: TaskView) => [...taskKeys.lists(userId), view] as const,
  detail: (userId: string, taskId: string) => [...taskKeys.all(userId), 'detail', taskId] as const,
  history: (userId: string, taskId: string) => [...taskKeys.detail(userId, taskId), 'history'] as const,
  deadlineRequests: (userId: string, taskId: string) =>
    [...taskKeys.detail(userId, taskId), 'deadline-requests'] as const,
};

/**
 * The signed-in user a view was opened for, or null once that account is gone (sign-out,
 * session end or a switch to another account). Views render their queries only while this
 * is non-null, so a page left over from the previous account never recreates cache entries
 * after teardown; the teardown redirect then replaces the page.
 */
export function useViewerId(session: SessionController): string | null {
  const user = useCurrentUser(session);
  const [owner] = useState(() => user?.id ?? null);
  return user !== null && user.id === owner ? user.id : null;
}

/** Retry a failed read once, but not when the server answered with a client error. */
function retryRead(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status < 500) return false;
  return failureCount < 1;
}

function nextCursor(lastPage: { nextCursor: string | null }, allPages: unknown[]): string | undefined {
  return allPages.length < MAX_PAGES ? (lastPage.nextCursor ?? undefined) : undefined;
}

export function useTask(api: ApiClients, userId: string, taskId: string) {
  return useQuery({
    queryKey: taskKeys.detail(userId, taskId),
    queryFn: async ({ signal }) =>
      unwrap(await api.tasks.GET('/tasks/{id}', { params: { path: { id: taskId } }, signal })),
    retry: retryRead,
  });
}

export function useTaskList(api: ApiClients, userId: string, view: TaskView) {
  return useInfiniteQuery({
    queryKey: taskKeys.list(userId, view),
    queryFn: async ({ pageParam, signal }) =>
      page(
        unwrap(
          await api.tasks.GET('/tasks', {
            params: { query: { view, limit: PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) } },
            signal,
          }),
        ),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: nextCursor,
    retry: retryRead,
  });
}

export function useTaskHistory(api: ApiClients, userId: string, taskId: string) {
  return useInfiniteQuery({
    queryKey: taskKeys.history(userId, taskId),
    queryFn: async ({ pageParam, signal }) =>
      page(
        unwrap(
          await api.tasks.GET('/tasks/{id}/history', {
            params: { path: { id: taskId }, query: { limit: PAGE_SIZE, ...(pageParam ? { cursor: pageParam } : {}) } },
            signal,
          }),
        ),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: nextCursor,
    retry: retryRead,
  });
}

/** A task has at most one pending request; the first page holds the recent ones. */
export function useDeadlineRequests(api: ApiClients, userId: string, taskId: string) {
  return useQuery({
    queryKey: taskKeys.deadlineRequests(userId, taskId),
    queryFn: async ({ signal }) =>
      page(
        unwrap(
          await api.tasks.GET('/tasks/{id}/deadline-requests', {
            params: { path: { id: taskId }, query: { limit: PAGE_SIZE } },
            signal,
          }),
        ),
      ).items,
    retry: retryRead,
  });
}

/** Directory search for the assignee combobox; the combobox trims and aborts stale queries. */
export async function searchUsers(api: ApiClients, query: string, signal: AbortSignal): Promise<UserSummary[]> {
  return page(unwrap(await api.tasks.GET('/users', { params: { query: { query, limit: 20 } }, signal }))).items;
}

/**
 * Applies a task the server returned after a confirmed mutation, then invalidates only what
 * that change can affect: the user's lists and the task's history and deadline requests.
 */
export function applyTaskUpdate(queryClient: QueryClient, userId: string, task: Task): void {
  queryClient.setQueryData(taskKeys.detail(userId, task.id), task);
  void queryClient.invalidateQueries({ queryKey: taskKeys.lists(userId) });
  void queryClient.invalidateQueries({ queryKey: taskKeys.history(userId, task.id) });
  void queryClient.invalidateQueries({ queryKey: taskKeys.deadlineRequests(userId, task.id) });
}

/** If-Match and Idempotency-Key headers for a mutation of a task at `version`. */
export function mutationHeaders(version: string, idempotencyKey: string) {
  return { 'If-Match': ifMatch(version), 'Idempotency-Key': idempotencyKey };
}

/**
 * One Idempotency-Key per distinct request. Re-sending an identical request (same operation,
 * version and body) reuses its key, so a retry after a network error or 5xx cannot apply a
 * committed mutation twice; any change takes a new key, because the server rejects a reused
 * key with a different body. Call `reset` after a confirmed success.
 */
export function useIdempotencyKeys() {
  const keys = useRef(new Map<string, string>());
  return useMemo(
    () => ({
      keyFor(request: unknown): string {
        const fingerprint = JSON.stringify(request);
        let key = keys.current.get(fingerprint);
        if (!key) {
          key = newIdempotencyKey();
          keys.current.set(fingerprint, key);
        }
        return key;
      },
      reset() {
        keys.current.clear();
      },
    }),
    [],
  );
}
