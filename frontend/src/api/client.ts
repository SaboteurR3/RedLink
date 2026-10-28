import type {
  Command,
  Conjunction,
  LinkStatus,
  Me,
  Mission,
  MissionRequest,
  Page,
  Photo,
  Problem,
  Rover,
  RoverCommand,
  Site,
  Tagged,
  Ticket,
} from './types';

/** An API error, carrying the server's RFC 9457 problem. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }

  /** One readable line plus the individual validation errors, for display. */
  describe(): string {
    const head = this.problem.title ? `${this.problem.title}: ${this.message}` : this.message;
    return this.problem.errors?.length ? `${head} (${this.problem.errors.join('; ')})` : head;
  }
}

/** The network failed before any HTTP answer arrived; the request may or may not have been processed. */
export class NetworkError extends Error {}

export type FetchFn = (input: string, init?: RequestInit) => Promise<Response>;

export type SubmitResult = { command: Command; replayed: boolean };

type SendOptions = {
  method?: string;
  body?: string;
  headers?: Record<string, string>;
  /** false for the login call, which has no token yet and whose 401 is not an expired session */
  auth?: boolean;
};

/**
 * Thin client for mission-control's REST API: bearer token, Problem Details errors, and safe
 * retries of command submission through Idempotency-Key.
 */
export class ApiClient {
  private token: string | null = null;

  constructor(
    private readonly fetchFn: FetchFn = (input, init) => fetch(input, init),
    private readonly onUnauthorized: () => void = () => {},
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((r) => setTimeout(r, ms)),
  ) {}

  setToken(token: string | null): void {
    this.token = token;
  }

  async login(username: string, password: string): Promise<{ token: string; expiresIn: number }> {
    const body = await this.request<{ access_token: string; expires_in: number }>('/api/v1/auth/token', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
      auth: false,
    });
    return { token: body.access_token, expiresIn: body.expires_in };
  }

  me(): Promise<Me> {
    return this.request('/api/v1/auth/me');
  }

  rover(roverId: number): Promise<Rover> {
    return this.request(`/api/v1/rovers/${roverId}`);
  }

  site(siteId: string): Promise<Site> {
    return this.request(`/api/v1/sites/${encodeURIComponent(siteId)}`);
  }

  commands(roverId: number, size = 50): Promise<Page<Command>> {
    return this.request(`/api/v1/rovers/${roverId}/commands?size=${size}`);
  }

  ticket(roverId: number): Promise<Ticket> {
    return this.request(`/api/v1/rovers/${roverId}/events/tickets`, { method: 'POST' });
  }

  /** Follows a HATEOAS link (e.g. `_links.cancel`) exactly as the server described it. */
  follow<T>(link: { href: string; method?: string }, body?: unknown): Promise<T> {
    return this.request(link.href, {
      method: link.method ?? 'GET',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  }

  photos(roverId: number, size = 24): Promise<Page<Photo>> {
    return this.request(`/api/v1/rovers/${roverId}/photos?size=${size}`);
  }

  /**
   * An image or other binary resource that needs the bearer token (an <img> tag cannot send it).
   * `accept` picks the representation where one URL has several (a photo is JSON or PNG).
   */
  async blob(path: string, accept = 'image/png'): Promise<Blob> {
    const response = await this.send(path, { headers: { Accept: `${accept}, application/problem+json` } });
    return response.blob();
  }

  /**
   * Sends a command. The Idempotency-Key is created once and reused on every retry, so a request
   * that reached the server before the network failed is replayed, not repeated: the rover can
   * never get the same command twice because of a flaky connection.
   */
  async submitCommand(roverId: number, command: RoverCommand, attempts = 3): Promise<SubmitResult> {
    const { body, replayed } = await this.postIdempotent<Command>(`/api/v1/rovers/${roverId}/commands`, command, attempts);
    return { command: body, replayed };
  }

  /** Plans a mission; retried exactly like {@link submitCommand}. */
  async submitMission(roverId: number, mission: MissionRequest, attempts = 3): Promise<{ mission: Mission; replayed: boolean }> {
    const { body, replayed } = await this.postIdempotent<Mission>(`/api/v1/rovers/${roverId}/missions`, mission, attempts);
    return { mission: body, replayed };
  }

  link(): Promise<LinkStatus> {
    return this.request('/api/v1/link');
  }

  /** The conjunction state with its ETag, for a conditional change. */
  async conjunction(): Promise<Tagged<Conjunction>> {
    const response = await this.send('/api/v1/link/conjunction', {});
    return { body: (await response.json()) as Conjunction, etag: response.headers.get('ETag') };
  }

  /**
   * Starts or ends a solar conjunction. With `etag` it is conditional (If-Match): if someone changed
   * it meanwhile the server answers 412 instead of silently overwriting their decision.
   */
  async setConjunction(active: boolean, etag: string | null): Promise<Tagged<Conjunction>> {
    const response = await this.send('/api/v1/link/conjunction', {
      method: 'PUT',
      body: JSON.stringify({ active }),
      headers: etag ? { 'If-Match': etag } : {},
    });
    return { body: (await response.json()) as Conjunction, etag: response.headers.get('ETag') };
  }

  missions(roverId: number, size = 10): Promise<Page<Mission>> {
    return this.request(`/api/v1/rovers/${roverId}/missions?size=${size}`);
  }

  private async postIdempotent<T>(path: string, payload: unknown, attempts: number): Promise<{ body: T; replayed: boolean }> {
    const key = crypto.randomUUID();
    for (let attempt = 1; ; attempt++) {
      try {
        const response = await this.send(path, {
          method: 'POST',
          body: JSON.stringify(payload),
          headers: { 'Idempotency-Key': key },
        });
        return {
          body: (await response.json()) as T,
          replayed: response.headers.get('Idempotent-Replayed') === 'true',
        };
      } catch (error) {
        if (!(error instanceof NetworkError) || attempt >= attempts) {
          throw error;
        }
        await this.sleep(500 * attempt);
      }
    }
  }

  private async request<T>(path: string, options: SendOptions = {}): Promise<T> {
    const response = await this.send(path, options);
    return (await response.json()) as T;
  }

  private async send(path: string, options: SendOptions): Promise<Response> {
    const headers: Record<string, string> = { Accept: 'application/json, application/problem+json', ...options.headers };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (options.auth !== false && this.token) {
      headers.Authorization = `Bearer ${this.token}`;
    }
    let response: Response;
    try {
      response = await this.fetchFn(path, { method: options.method ?? 'GET', body: options.body, headers });
    } catch (error) {
      throw new NetworkError(error instanceof Error ? error.message : 'Network error');
    }
    if (!response.ok) {
      const problem = await readProblem(response);
      if (response.status === 401 && options.auth !== false) {
        this.onUnauthorized();
      }
      throw new ApiError(response.status, problem);
    }
    return response;
  }
}

async function readProblem(response: Response): Promise<Problem> {
  try {
    return (await response.json()) as Problem;
  } catch {
    return { status: response.status, title: response.statusText || `HTTP ${response.status}` };
  }
}
