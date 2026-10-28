import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RoverStream, type EventSourceLike, type StreamHandlers } from './events';
import type { Ticket } from './types';

class FakeSource implements EventSourceLike {
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private listeners = new Map<string, (event: MessageEvent<string>) => void>();

  constructor(readonly url: string) {}

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, listener);
  }

  close(): void {
    this.closed = true;
  }

  emit(type: string, id: string, data: unknown): void {
    this.listeners.get(type)?.({ lastEventId: id, data: JSON.stringify(data) } as MessageEvent<string>);
  }
}

describe('RoverStream', () => {
  let sources: FakeSource[];
  let handlers: StreamHandlers & { events: Array<[string, unknown]>; statuses: string[] };
  let ticketCount: number;
  const api = {
    ticket: async (): Promise<Ticket> => {
      ticketCount++;
      return { ticket: `t${ticketCount}`, expiresAt: '', streamUrl: `/api/v1/rovers/1/events?ticket=t${ticketCount}` };
    },
  };

  beforeEach(() => {
    vi.useFakeTimers();
    sources = [];
    ticketCount = 0;
    const events: Array<[string, unknown]> = [];
    const statuses: string[] = [];
    handlers = {
      events,
      statuses,
      onEvent: (name, data) => events.push([name, data]),
      onStatus: (status) => statuses.push(status),
      onAuthError: vi.fn(),
    };
  });

  afterEach(() => vi.useRealTimers());

  const stream = () =>
    new RoverStream(api, 1, handlers, (url) => {
      const source = new FakeSource(url);
      sources.push(source);
      return source;
    });

  it('opens with a fresh ticket and delivers named events', async () => {
    stream().start();
    await vi.runAllTimersAsync();

    sources[0]!.onopen?.(new Event('open'));
    sources[0]!.emit('command', '5', { id: 9, status: 'QUEUED' });

    expect(sources[0]!.url).toBe('/api/v1/rovers/1/events?ticket=t1');
    expect(handlers.events).toEqual([['command', { id: 9, status: 'QUEUED' }]]);
    expect(handlers.statuses).toContain('live');
  });

  it('after an error reconnects with a NEW ticket and resumes after the last event seen', async () => {
    stream().start();
    await vi.runAllTimersAsync();
    sources[0]!.emit('rover', '41', {});
    sources[0]!.emit('command', '42', {});

    sources[0]!.onerror?.(new Event('error'));
    expect(sources[0]!.closed).toBe(true);
    await vi.advanceTimersByTimeAsync(RoverStream.backoff(1));

    expect(sources[1]!.url).toBe('/api/v1/rovers/1/events?ticket=t2&lastEventId=42');
    expect(handlers.statuses).toContain('reconnecting');
  });

  it('backs off up to 10 seconds', () => {
    expect([1, 2, 3, 4, 5, 9].map(RoverStream.backoff)).toEqual([1000, 2000, 4000, 8000, 10000, 10000]);
  });

  it('an expired login stops reconnecting', async () => {
    const expired = { ticket: async () => Promise.reject(Object.assign(new Error('401'), { status: 401 })) };
    new RoverStream(expired, 1, handlers, (url) => new FakeSource(url)).start();
    await vi.runAllTimersAsync();

    expect(handlers.onAuthError).toHaveBeenCalledOnce();
  });

  it('stop() closes the stream and prevents reconnects', async () => {
    const s = stream();
    s.start();
    await vi.runAllTimersAsync();

    s.stop();
    sources[0]!.onerror?.(new Event('error'));
    await vi.advanceTimersByTimeAsync(20_000);

    expect(sources).toHaveLength(1);
    expect(sources[0]!.closed).toBe(true);
  });
});
