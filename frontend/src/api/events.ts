import type { ApiClient } from './client';

export const STREAM_EVENTS = ['rover', 'command', 'uplink', 'rover-event', 'photo', 'link', 'reset'] as const;
export type StreamEventName = (typeof STREAM_EVENTS)[number];
export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'stopped';

export type StreamHandlers = {
  onEvent: (name: StreamEventName, data: unknown) => void;
  onStatus: (status: StreamStatus) => void;
  /** Getting a new ticket failed with 401: the login has expired. */
  onAuthError: () => void;
};

/** The part of EventSource the stream uses (lets tests supply a fake). */
export type EventSourceLike = {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  close(): void;
};

/**
 * A rover's live event stream with automatic resume.
 *
 * EventSource cannot send an Authorization header, so every connection uses a fresh single-use
 * stream ticket. Because the ticket is used up, EventSource's own reconnect would fail; on any
 * error we therefore close it, fetch a new ticket and reconnect ourselves, passing the last event
 * id we saw so the server replays exactly what we missed.
 */
export class RoverStream {
  private source: EventSourceLike | null = null;
  private lastEventId: string | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private stopped = false;

  constructor(
    private readonly api: Pick<ApiClient, 'ticket'>,
    private readonly roverId: number,
    private readonly handlers: StreamHandlers,
    private readonly createSource: (url: string) => EventSourceLike = (url) => new EventSource(url),
  ) {}

  start(): void {
    this.stopped = false;
    void this.open();
  }

  stop(): void {
    this.stopped = true;
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.source?.close();
    this.source = null;
    this.handlers.onStatus('stopped');
  }

  /** Delay before reconnect attempt n: 1 s, 2 s, 4 s … up to 10 s. */
  static backoff(failures: number): number {
    return Math.min(10_000, 1_000 * 2 ** Math.max(0, failures - 1));
  }

  private async open(): Promise<void> {
    this.handlers.onStatus(this.failures === 0 ? 'connecting' : 'reconnecting');
    let url: string;
    try {
      const ticket = await this.api.ticket(this.roverId);
      url = this.lastEventId === null ? ticket.streamUrl : `${ticket.streamUrl}&lastEventId=${encodeURIComponent(this.lastEventId)}`;
    } catch (error) {
      if ((error as { status?: number }).status === 401) {
        this.handlers.onAuthError();
        return;
      }
      this.scheduleReconnect();
      return;
    }
    if (this.stopped) return;

    const source = this.createSource(url);
    this.source = source;
    source.onopen = () => {
      this.failures = 0;
      this.handlers.onStatus('live');
    };
    source.onerror = () => {
      source.close();
      if (this.source === source) this.scheduleReconnect();
    };
    for (const name of STREAM_EVENTS) {
      source.addEventListener(name, (event) => {
        if (event.lastEventId) this.lastEventId = event.lastEventId;
        this.handlers.onEvent(name, JSON.parse(event.data));
      });
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    this.source = null;
    this.failures++;
    this.handlers.onStatus('reconnecting');
    this.retryTimer = setTimeout(() => void this.open(), RoverStream.backoff(this.failures));
  }
}
