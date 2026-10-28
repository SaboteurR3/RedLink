import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError, NetworkError, type FetchFn } from './client';

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });

const noSleep = () => Promise.resolve();

describe('ApiClient', () => {
  it('sends the bearer token', async () => {
    const fetchFn = vi.fn<FetchFn>().mockResolvedValue(json(200, { username: 'operator', roles: ['OPERATOR'] }));
    const api = new ApiClient(fetchFn);
    api.setToken('abc');

    await api.me();

    const init = fetchFn.mock.calls[0]?.[1];
    expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer abc');
  });

  it('asks for JSON by default and for the image representation of a photo', async () => {
    const fetchFn = vi
      .fn<FetchFn>()
      .mockResolvedValueOnce(json(200, { content: [], page: { size: 24, number: 0, totalElements: 0, totalPages: 0 } }))
      .mockResolvedValueOnce(new Response(new Uint8Array([0x89, 0x50]), { status: 200, headers: { 'Content-Type': 'image/png' } }));
    const api = new ApiClient(fetchFn);

    await api.photos(1);
    const blob = await api.blob('/api/v1/photos/abc', 'image/png');

    const accept = (call: number) => (fetchFn.mock.calls[call]?.[1]?.headers as Record<string, string>).Accept;
    expect(fetchFn.mock.calls[0]?.[0]).toBe('/api/v1/rovers/1/photos?size=24');
    expect(accept(0)).toBe('application/json, application/problem+json');
    expect(accept(1)).toBe('image/png, application/problem+json');
    expect(blob.size).toBe(2);
  });

  it('plans a mission with an Idempotency-Key that survives a retry', async () => {
    const fetchFn = vi
      .fn<FetchFn>()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(json(202, { id: 4, status: 'QUEUED' }, { 'Idempotent-Replayed': 'true' }));
    const api = new ApiClient(fetchFn, () => {}, noSleep);
    const plan = { name: 'Survey', steps: [{ type: 'TAKE_PHOTO' as const, camera: 'NAVCAM' as const }] };

    const { mission, replayed } = await api.submitMission(1, plan);

    const key = (call: number) => (fetchFn.mock.calls[call]?.[1]?.headers as Record<string, string>)['Idempotency-Key'];
    expect(fetchFn.mock.calls[0]?.[0]).toBe('/api/v1/rovers/1/missions');
    expect(fetchFn.mock.calls[1]?.[1]?.body).toBe(JSON.stringify(plan));
    expect(key(0)).toBeTruthy();
    expect(key(1)).toBe(key(0));
    expect(mission.id).toBe(4);
    expect(replayed).toBe(true);
  });

  it('turns Problem Details into a readable ApiError', async () => {
    const api = new ApiClient(async () =>
      json(400, {
        type: 'https://redlink.example/problems/validation-error',
        title: 'Validation failed',
        status: 400,
        detail: 'The request body is invalid',
        errors: ['distanceMeters: must be greater than or equal to 1'],
      }),
    );

    const error = await api.me().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).problem.type).toBe('https://redlink.example/problems/validation-error');
    expect((error as ApiError).describe()).toBe(
      'Validation failed: The request body is invalid (distanceMeters: must be greater than or equal to 1)',
    );
  });

  it('reports a 401 so the app can return to the login screen', async () => {
    const onUnauthorized = vi.fn();
    const api = new ApiClient(async () => json(401, { title: 'Unauthenticated' }), onUnauthorized);

    await expect(api.me()).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('a failed login is not an expired session', async () => {
    const onUnauthorized = vi.fn();
    const api = new ApiClient(async () => json(401, { title: 'Invalid credentials' }), onUnauthorized);

    await expect(api.login('operator', 'wrong')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  describe('submitCommand', () => {
    it('retries a network failure with the SAME Idempotency-Key', async () => {
      const fetchFn = vi
        .fn<FetchFn>()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(json(202, { id: 7, status: 'QUEUED' }, { 'Idempotent-Replayed': 'true' }));
      const api = new ApiClient(fetchFn, () => {}, noSleep);

      const result = await api.submitCommand(1, { type: 'MOVE', distanceMeters: 5 });

      const keys = fetchFn.mock.calls.map((call) => (call[1]?.headers as Record<string, string>)['Idempotency-Key']);
      expect(keys).toHaveLength(2);
      expect(keys[0]).toBeTruthy();
      expect(keys[1]).toBe(keys[0]);
      expect(result.replayed).toBe(true);
      expect(result.command.id).toBe(7);
    });

    it('does not retry an answer from the server', async () => {
      const fetchFn = vi.fn<FetchFn>().mockResolvedValue(json(422, { title: 'Insufficient battery' }));
      const api = new ApiClient(fetchFn, () => {}, noSleep);

      await expect(api.submitCommand(1, { type: 'MOVE', distanceMeters: 5 })).rejects.toBeInstanceOf(ApiError);
      expect(fetchFn).toHaveBeenCalledOnce();
    });

    it('gives up after the last attempt', async () => {
      const fetchFn = vi.fn<FetchFn>().mockRejectedValue(new TypeError('offline'));
      const api = new ApiClient(fetchFn, () => {}, noSleep);

      await expect(api.submitCommand(1, { type: 'REBOOT' }, 3)).rejects.toBeInstanceOf(NetworkError);
      expect(fetchFn).toHaveBeenCalledTimes(3);
    });

    it('uses a new key for every new submission', async () => {
      const fetchFn = vi.fn<FetchFn>().mockImplementation(async () => json(202, { id: 1 }));
      const api = new ApiClient(fetchFn);

      await api.submitCommand(1, { type: 'REBOOT' });
      await api.submitCommand(1, { type: 'REBOOT' });

      const keys = fetchFn.mock.calls.map((call) => (call[1]?.headers as Record<string, string>)['Idempotency-Key']);
      expect(keys[0]).not.toBe(keys[1]);
    });
  });

  it('follows a HATEOAS link with its method', async () => {
    const fetchFn = vi.fn<FetchFn>().mockResolvedValue(json(200, { id: 3, status: 'CANCELLED' }));
    const api = new ApiClient(fetchFn);

    await api.follow({ href: '/api/v1/commands/3/cancel', method: 'POST' });

    expect(fetchFn.mock.calls[0]?.[0]).toBe('/api/v1/commands/3/cancel');
    expect(fetchFn.mock.calls[0]?.[1]?.method).toBe('POST');
  });
});

describe('ApiClient.follow with a body', () => {
  it('sends the body as JSON (reject with a reason)', async () => {
    const fetchFn = vi.fn<FetchFn>().mockResolvedValue(json(200, { id: 3, status: 'REJECTED' }));
    const api = new ApiClient(fetchFn);

    await api.follow({ href: '/api/v1/commands/3/reject', method: 'POST' }, { reason: 'Too risky' });

    const init = fetchFn.mock.calls[0]?.[1];
    expect(init?.body).toBe('{"reason":"Too risky"}');
    expect((init?.headers as Record<string, string>)['Content-Type']).toBe('application/json');
  });
});
