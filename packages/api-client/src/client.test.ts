import { describe, expect, it, vi } from 'vitest';
import { createApiClients, newIdempotencyKey } from './client';

describe('createApiClients', () => {
  it('sends the bearer token and forwards the AbortSignal', async () => {
    const seen: Request[] = [];
    const fetch = vi.fn(async (request: Request) => {
      seen.push(request);
      return new Response(JSON.stringify({ count: 3 }), { headers: { 'Content-Type': 'application/json' } });
    });
    const { notifications } = createApiClients({ baseUrl: 'http://app.test/api/v1', getAccessToken: () => 'token-1', fetch });
    const controller = new AbortController();

    const { data } = await notifications.GET('/notifications/unread-count', { signal: controller.signal });
    controller.abort();

    expect(data).toEqual({ count: 3 });
    expect(seen[0]?.url).toBe('http://app.test/api/v1/notifications/unread-count');
    expect(seen[0]?.headers.get('Authorization')).toBe('Bearer token-1');
    expect(seen[0]?.signal.aborted).toBe(true);
  });

  it('omits Authorization when signed out and reports 401s', async () => {
    const onUnauthenticated = vi.fn();
    const fetch = vi.fn(async (request: Request) => {
      expect(request.headers.has('Authorization')).toBe(false);
      return new Response('{}', { status: 401, headers: { 'Content-Type': 'application/problem+json' } });
    });
    const { tasks } = createApiClients({ baseUrl: 'http://app.test/api/v1', getAccessToken: () => null, onUnauthenticated, fetch });

    await tasks.GET('/tasks/{id}', { params: { path: { id: '9007199254740993' } } });

    expect(onUnauthenticated).toHaveBeenCalledOnce();
  });

  it('keeps decimal-string IDs intact in paths', async () => {
    const fetch = vi.fn(async (_request: Request) => new Response('{}', { headers: { 'Content-Type': 'application/json' } }));
    const { tasks } = createApiClients({ baseUrl: 'http://app.test/api/v1', getAccessToken: () => 't', fetch });

    await tasks.GET('/tasks/{id}', { params: { path: { id: '9223372036854775807' } } });

    expect(fetch.mock.calls[0]?.[0].url).toBe('http://app.test/api/v1/tasks/9223372036854775807');
  });

  it('creates UUID idempotency keys', () => {
    expect(newIdempotencyKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});
