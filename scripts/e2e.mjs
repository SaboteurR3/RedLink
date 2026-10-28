#!/usr/bin/env node
// End-to-end test of the whole system through its public front door (ticket 20). No dependencies:
// Node 22's fetch only. It talks HTTP like any client would, so it works against
//   docker compose:           REDLINK_URL=http://localhost:8088  (nginx → mission-control)
//   the Vite dev server:      REDLINK_URL=http://localhost:5173
//   mission-control directly: REDLINK_URL=http://localhost:8080
// Users viewer / operator / director with password REDLINK_PASSWORD (default: the local dev one).
// Exit code 0 when every step passed.

const BASE = (process.env.REDLINK_URL ?? 'http://localhost:8088').replace(/\/$/, '');
const PASSWORD = process.env.REDLINK_PASSWORD ?? 'redlink-dev';
const ROVER = 1;
const TIMEOUT_MS = Number(process.env.REDLINK_E2E_TIMEOUT_MS ?? 180_000);

let failures = 0;

async function step(name, fn) {
  const started = Date.now();
  try {
    const detail = await fn();
    console.log(`  ok    ${name}${detail ? ` — ${detail}` : ''} (${Date.now() - started} ms)`);
  } catch (error) {
    failures++;
    console.log(`  FAIL  ${name} — ${error.message}`);
  }
}

function check(condition, message) {
  if (!condition) throw new Error(message);
}

async function api(path, { token, method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      Accept: 'application/json, application/problem+json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const type = response.headers.get('content-type') ?? '';
  const json = type.includes('json') && response.status !== 304 ? await response.json() : null;
  return { response, status: response.status, json };
}

async function login(username) {
  const { status, json } = await api('/api/v1/auth/token', { method: 'POST', body: { username, password: PASSWORD } });
  check(status === 200, `login ${username}: HTTP ${status}`);
  return json.access_token;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function waitFor(what, probe, timeoutMs = TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const result = await probe();
    if (result) return result;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(1000);
  }
}

/** Reads a server-sent event stream in the background and keeps every event. */
function openStream(url, signal) {
  const events = [];
  const done = (async () => {
    const response = await fetch(BASE + url, { headers: { Accept: 'text/event-stream' }, signal });
    check(response.status === 200, `event stream: HTTP ${response.status}`);
    const decoder = new TextDecoder();
    let buffer = '';
    for await (const chunk of response.body) {
      buffer += decoder.decode(chunk, { stream: true });
      let end;
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, end);
        buffer = buffer.slice(end + 2);
        const name = /^event:(.*)$/m.exec(block)?.[1]?.trim();
        const data = [...block.matchAll(/^data:(.*)$/gm)].map((m) => m[1]).join('\n');
        if (name) events.push({ name, data: data ? JSON.parse(data) : null });
      }
    }
  })().catch((error) => {
    if (error.name !== 'AbortError') events.push({ name: 'stream-error', data: error.message });
  });
  return { events, done };
}

const OPPOSITE = { NORTH: 'SOUTH', SOUTH: 'NORTH', EAST: 'WEST', WEST: 'EAST' };
const UNFINISHED = new Set(['PENDING_APPROVAL', 'QUEUED', 'TRANSMITTED', 'RECEIVED', 'EXECUTING']);

console.log(`RedLink end-to-end test against ${BASE}`);
const abort = new AbortController();
let operator;
let viewer;
let stream;
let mission;
let rover;

await step('log in as operator and viewer', async () => {
  operator = await login('operator');
  viewer = await login('viewer');
});

await step('no token → 401 problem', async () => {
  const { status, json, response } = await api(`/api/v1/rovers/${ROVER}`);
  check(status === 401, `HTTP ${status}`);
  check(response.headers.get('content-type')?.startsWith('application/problem+json'), 'not a problem document');
  check(json.type === 'https://redlink.example/problems/unauthenticated', `type ${json.type}`);
});

await step('rover has no unfinished commands (a mission plans from a known state)', async () => {
  await waitFor('the rover to be idle', async () => {
    const { json } = await api(`/api/v1/rovers/${ROVER}/commands?size=50`, { token: viewer });
    return !json.content.some((c) => UNFINISHED.has(c.status));
  });
  rover = (await api(`/api/v1/rovers/${ROVER}`, { token: viewer })).json;
  const k = rover.lastKnown;
  return `at (${k.x}, ${k.y}) facing ${k.heading}, battery ${k.batteryPercent} %, storage ${k.storageUsedPercent} %`;
});

await step('open the live event stream with a single-use ticket', async () => {
  const { status, json } = await api(`/api/v1/rovers/${ROVER}/events/tickets`, { token: viewer, method: 'POST' });
  check(status === 201, `ticket: HTTP ${status}`);
  stream = openStream(json.streamUrl, abort.signal);
  await waitFor('the snapshot event', async () => stream.events.some((e) => e.name === 'rover'), 15_000);
});

const key = crypto.randomUUID();
const plan = {
  name: `E2E survey ${new Date().toISOString()}`,
  steps: [
    { type: 'TAKE_PHOTO', camera: 'NAVCAM' },
    { type: 'ROTATE', heading: 'NORTH' }, // replaced below by the opposite of the current heading
    { type: 'TAKE_PHOTO', camera: 'MASTCAM' },
  ],
};

await step('plan a mission → 202, Location, forecast', async () => {
  plan.steps[1].heading = OPPOSITE[rover?.lastKnown?.heading ?? 'SOUTH'];
  const { status, json, response } = await api(`/api/v1/rovers/${ROVER}/missions`, {
    token: operator,
    method: 'POST',
    body: plan,
    headers: { 'Idempotency-Key': key },
  });
  check(status === 202, `HTTP ${status} ${JSON.stringify(json)}`);
  check(response.headers.get('location') === `/api/v1/missions/${json.id}`, `Location ${response.headers.get('location')}`);
  check(json.forecast.heading === plan.steps[1].heading, `forecast heading ${json.forecast.heading}`);
  mission = json;
  return `mission #${json.id}, ${json.steps.length} steps, forecast (${json.forecast.x}, ${json.forecast.y}) ${json.forecast.heading}`;
});

await step('same Idempotency-Key again → replayed, byte-identical', async () => {
  const first = JSON.stringify(mission);
  const { status, json, response } = await api(`/api/v1/rovers/${ROVER}/missions`, {
    token: operator,
    method: 'POST',
    body: plan,
    headers: { 'Idempotency-Key': key },
  });
  check(status === 202, `HTTP ${status}`);
  check(response.headers.get('idempotent-replayed') === 'true', 'no Idempotent-Replayed header');
  check(JSON.stringify(json) === first, 'body differs from the first answer');
});

await step('mission completes on Mars and Earth hears about it (polling with If-None-Match)', async () => {
  let etag = null;
  let notModified = 0;
  const done = await waitFor('the mission to finish', async () => {
    const { status, json, response } = await api(`/api/v1/missions/${mission.id}`, {
      token: viewer,
      headers: etag ? { 'If-None-Match': etag } : {},
    });
    if (status === 304) {
      notModified++;
      return null;
    }
    check(status === 200, `HTTP ${status}`);
    etag = response.headers.get('etag');
    return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(json.status) ? json : null;
  });
  check(done.status === 'COMPLETED', `mission ${done.status}: ${JSON.stringify(done.steps.map((s) => s.failure))}`);
  check(notModified > 0, 'never got a 304 while waiting');
  return `${done.progress.completed}/${done.progress.total} steps, ${notModified}× 304 while waiting`;
});

await step('the event stream pushed the steps and the photos', async () => {
  const stepIds = new Set(mission.steps.map((s) => s.id));
  const completed = stream.events.filter((e) => e.name === 'command' && stepIds.has(e.data.id) && e.data.status === 'COMPLETED');
  const photos = stream.events.filter((e) => e.name === 'photo' && stepIds.has(e.data.commandId));
  const errors = stream.events.filter((e) => e.name === 'stream-error');
  check(errors.length === 0, `stream error: ${errors[0]?.data}`);
  check(completed.length === 3, `${completed.length} COMPLETED step events`);
  check(photos.length === 2, `${photos.length} photo events`);
  return `${stream.events.length} events received`;
});

await step('photo by content negotiation: JSON, PNG, and 406', async () => {
  const last = mission.steps[2].id;
  const { json: page } = await api(`/api/v1/rovers/${ROVER}/photos?size=5`, { token: viewer });
  const photo = page.content.find((p) => p.commandId === last);
  check(photo, 'no photo for the last step');
  check(photo.camera === 'MASTCAM', `camera ${photo.camera}`);

  const png = await fetch(`${BASE}/api/v1/photos/${photo.id}`, { headers: { Authorization: `Bearer ${viewer}`, Accept: 'image/png' } });
  check(png.status === 200, `PNG: HTTP ${png.status}`);
  check(png.headers.get('content-type') === 'image/png', `content-type ${png.headers.get('content-type')}`);
  check(/accept/i.test(png.headers.get('vary') ?? ''), `Vary ${png.headers.get('vary')}`);
  const bytes = new Uint8Array(await png.arrayBuffer());
  check(bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47, 'not a PNG');

  const text = await fetch(`${BASE}/api/v1/photos/${photo.id}`, { headers: { Authorization: `Bearer ${viewer}`, Accept: 'text/plain' } });
  check(text.status === 406, `text/plain: HTTP ${text.status}`);
  return `${photo.width}×${photo.height}, ${bytes.length} bytes`;
});

await step('unknown mission → 404 problem', async () => {
  const { status, json } = await api('/api/v1/missions/999999999', { token: viewer });
  check(status === 404, `HTTP ${status}`);
  check(json.type === 'https://redlink.example/problems/mission-not-found', `type ${json.type}`);
});

abort.abort();
await stream?.done;
console.log(failures === 0 ? 'All steps passed.' : `${failures} step(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
