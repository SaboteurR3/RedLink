# 12 — SSE event stream

Status: ready-for-human
Source spec: ../SPEC.md §8 (`/rovers/{id}/events`), §14.5

## Scope

Push everything that happens to a rover to browsers as it happens: the 3D scene (ticket 18) only
moves on these events.

### Stream: `GET /api/v1/rovers/{id}/events` (`text/event-stream`, VIEWER)
- Events (`event:` name, `data:` JSON, `id:` for resume):
  - `rover`: the rover (`RoverResponse`) — sent first as a snapshot, then on every change.
  - `command`: a command (`CommandResponse`, with `_links`) on create, cancel, transmit, every
    status change from telemetry, uplink rejection.
  - `uplink`: `{commandId, sequenceNo, transmittedAt, arrivesAt}` when a command leaves Earth
    (for the signal-beam animation).
  - `rover-event`: rover EVENT telemetry (`code`, `severity`, `message`, `roverTime`).
  - `reset`: the requested resume point is too old; carries a fresh `rover` snapshot.
- `retry: 3000`; a `: keepalive` comment every 15 s; `Cache-Control: no-cache`,
  `X-Accel-Buffering: no` (no proxy buffering).
- **Resume**: `Last-Event-ID` header (sent by EventSource when it reconnects) or
  `?lastEventId=` → missed events replayed in order (up to 500, else `reset`), then live.
- Max subscribers per instance (500) → `503` + `Retry-After`.

### Authentication for browsers: stream tickets
- `POST /api/v1/rovers/{id}/events/tickets` (VIEWER, bearer) → `201
  {ticket, expiresAt, streamUrl}`: random 256-bit, **single-use**, valid 30 s, bound to that
  rover and to the caller's roles.
- `GET .../events?ticket=…` authenticates with the ticket; reused, expired or other-rover
  ticket → 401. Bearer `Authorization` keeps working for non-browser clients.
- Why: `EventSource` cannot send headers, and a JWT in a URL ends up in logs and history.

### Delivery guarantees
- Events are stored in `rover_events` in the **same transaction** as the change and pushed only
  **after commit** (`@TransactionalEventListener`): rolled-back changes are never streamed.
- Async dispatches of streaming requests are permitted in the security chain (the original
  request was already authorized).
- Events older than 24 h are purged hourly.
- Single instance: subscribers and tickets live in memory; several instances would need a shared
  bus (PostgreSQL `LISTEN/NOTIFY`) and shared tickets — future work, SPEC §11.

### Data
- Flyway `V6`: `rover_events` (id identity = SSE id, rover, type, data, created_at).

## Out of scope

Frontend client (13), photos (16).

## Acceptance criteria

- Ticket: issued 201; works once; reused/expired/other rover → 401; no auth → 401.
- First event is the `rover` snapshot.
- A new command and its telemetry appear as `command` events; a transmission as `uplink`.
- A request that fails (422) produces no event.
- `Last-Event-ID` replays exactly the missed events in order.
- Bearer header works too.

## Checklist

- [x] Ticket detailed
- [x] V6 migration
- [x] `EventProperties`, `RoverEventPublisher` (outbox + after-commit), `SseHub`
- [x] Stream tickets + security filter + async dispatch
- [x] `EventsController`
- [x] Publish from commands, uplink, telemetry, rover rename
- [x] Integration tests
- [x] SPEC updated
- [x] `./mvnw verify -Plocal-db` green: 138 unit + 103 integration tests
- [x] Live check with curl through Tomcat: ticket 201 no-store, stream text/event-stream with snapshot (retry 3000) then a live command event on submit, reused ticket 401

## Notes for later tickets

- Ticket 13 (frontend): flow = POST ticket → `new EventSource(streamUrl)`; listen for `rover`,
  `command`, `uplink`, `rover-event`, `reset`. On `error` (the ticket is used up, so EventSource's
  own reconnect fails with 401): get a new ticket and open
  `streamUrl + "&lastEventId=" + lastSeenId`. On `reset`: reload state.
- Ticket 15/16/17/19: publish new events through `RoverEventPublisher.publish` inside the
  transaction that makes the change.
- Known limit: ids come from an identity column, and two transactions can commit out of id order.
  A client that reconnects in the milliseconds between them could miss the lower id. Live clients
  never miss anything (each event is pushed when its own transaction commits). Acceptable here;
  fix if needed by reading events only up to the oldest in-flight id.
- Delivery happens on the committing thread; one very slow client slows that request. With
  many clients, hand delivery to an executor per subscription.
- Dev database: command 3 (TAKE_PHOTO) is QUEUED from the live check (uplink was off).
