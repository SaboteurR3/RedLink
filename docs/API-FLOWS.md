# RedLink — every flow, call by call

What happens behind each screen and button: which HTTP calls are made, what the server does with
them (class by class), what is stored, and what the UI shows at each moment.

Read [HOW-IT-WORKS.md](HOW-IT-WORKS.md) first for the big picture. Default timings below are from
`application.yml` (demo values; real Mars would be minutes to hours).

## The cast

```
 Browser (React)                mission-control :8080 (Earth)                 rover-sim :8081 (Mars)
 ───────────────                ─────────────────────────────                 ──────────────────────
 ApiClient (fetch + JWT)  ──►   /api/v1/**  (JWT, roles)
 RoverStream (SSE)        ◄──   /api/v1/rovers/{id}/events
                                UplinkScheduler   every 500 ms ── POST /flight/uplink (HMAC) ──►  UplinkController
                                DownlinkController ◄── POST /dsn/downlink (HMAC) ───── DownlinkScheduler every 500 ms
                                WebhookScheduler  ── POST <your URL> (HMAC) ──► external receivers
                                                                               CommandStepper (flight loop)
```

| Timing | Default | Where |
|---|---|---|
| Hold before a QUEUED command is transmitted (last chance to cancel) | 5 s | `redlink.uplink.queue-hold` |
| Light time Earth → Mars | 10 s | `redlink.uplink.one-way-delay` |
| Light time Mars → Earth | 10 s | rover-sim `redlink.link.one-way-delay` |
| Schedulers look for work | every 500 ms | `poll-interval` |
| One MOVE meter / ROTATE / photo / drill / charge minute / reboot | 1 s / 2 s / 3 s / 15 s / 1 s / 5 s | `RoverSpecs` |

So a 5 m MOVE sent now: ~5 s on Earth + 10 s to Mars + 5 s driving + 10 s for the last report to
come back ≈ **30 s** until the UI says COMPLETED.

## Errors are always the same shape

Every error is RFC 9457 **Problem Details** (`application/problem+json`):

```json
{ "type": "https://redlink.example/problems/rover-in-safe-mode", "title": "Rover in safe mode",
  "status": 422, "detail": "Rover 1 was last reported in safe mode; it only accepts REBOOT", "instance": "/api/v1/rovers/1/commands" }
```

The UI shows `title` / `detail` next to the button that failed. A **401 anywhere** logs you out with
"Your session expired" (`ApiClient.onUnauthorized` → `App.logout`).

---

## 1. Opening the app and logging in

| Step | Call | Server | UI |
|---|---|---|---|
| Page loads, a token is saved in the tab | `GET /api/v1/auth/me` | Spring Security checks the JWT signature and expiry; `AuthController.me` returns username, roles, expiry | "Connecting to mission control…" splash, then the rover picker |
| No token | — | — | **Login page**; the intro film's code is preloaded meanwhile |
| You press Sign in | `POST /api/v1/auth/token` `{username, password}` (public) | `AuthService.login`: `LoginThrottle` first (5 failures / 15 min per user+IP → **429** + `Retry-After`), then BCrypt check (wrong → **401**, generic message so usernames can't be probed), then `TokenService` signs an HS256 JWT with the roles; `Cache-Control: no-store` | Token saved in `sessionStorage`; **intro film starts at once**; then `GET /auth/me` |

Roles are cumulative: `VIEWER` ⊂ `OPERATOR` ⊂ `FLIGHT_DIRECTOR`. The UI hides buttons your role
can't use, but **the server checks every call** with `@PreAuthorize` (wrong role → **403**).

## 2. Rover picker → mission-control screen

| Step | Call | Server | UI |
|---|---|---|---|
| Picker opens | `GET /api/v1/rovers` | `RoverService.list` → page of `RoverResponse` (Earth's *last known* state, from the `rovers` table) | Dropdown: Perseverance-sim, Curiosity-sim; ⚠ on one in safe mode |
| You pick a rover; all in parallel: | `GET /rovers/{id}` | Rover + **ETag** (from `@Version`), `Cache-Control: no-cache, private` | Status panel, battery, position |
| | `GET /rovers/{id}/commands` and `?status=PENDING_APPROVAL` | Newest 50 commands + all waiting approvals | Command timeline, approval inbox |
| | `GET /rovers/{id}/photos` | Photo metadata (no image bytes) | Photo strip placeholders |
| | `GET /link` | Light time, relay window, conjunction | Link panel |
| | `GET /rovers/{id}/telemetry?type=SAMPLE` | History from the `telemetry` table | Drill marks on the map |
| | `GET /specs` | `RoverSpecs` constants + `solSeconds`; cached 1 h | Battery/time estimates in the console |
| | `GET /sites/{siteId}`, `/heightmap`, `/surface` | Site info + the two PNGs; `Cache-Control: max-age=86400` (they never change) | 3D terrain / 2D map is built |
| | `GET /photos/{id}` with `Accept: image/png` per photo | **Same URL**, other `produces` → PNG bytes (content negotiation) | Thumbnails appear |
| Missions panel | `GET /rovers/{id}/missions?size=8` | `MissionService.list` | Your missions with progress |
| Live updates | see section 3 | | "● live" in the top bar |

## 3. Live updates (Server-Sent Events)

A browser's `EventSource` cannot send an `Authorization` header, so:

1. `POST /api/v1/rovers/{id}/events/tickets` → **201** `{ticket, expiresAt, streamUrl}`. The ticket
   is single-use and short-lived (`StreamTickets`); `no-store` because it is a credential.
2. `GET {streamUrl}` (`?ticket=…`) → `text/event-stream`. `StreamTicketFilter` consumes the ticket
   (bad/used → **401**); `SseHub.subscribe` registers the connection (too many → **503**).
3. The server first sends a `rover` snapshot, then every event as it happens. Events are written to
   `rover_events` and fanned out with PostgreSQL `NOTIFY`, so any server instance can deliver them.
4. On disconnect, `RoverStream` gets a **new ticket** and reconnects with `lastEventId=<last seen>`;
   the hub replays what was missed from `rover_events`. Back-off 1 s, 2 s, 4 s … max 10 s.

| SSE event | Sent when | UI reaction |
|---|---|---|
| `rover` | Telemetry changed the rover | Rover moves in 3D, battery/mode update, safe-mode frame |
| `command` | Any status change of a command | Timeline row updates; toast on COMPLETED / FAILED / REFUSED |
| `uplink` | A command left Earth | "In flight" countdown until it reaches Mars; signal beam in 3D |
| `rover-event` | Rover EVENT telemetry (dust storm, safe mode, no sunlight…) | Events list + toast |
| `photo` | A photo arrived | New photo in the strip + pin on the map |
| `sample` | A drill result arrived | Drill mark + toast |
| `link` | Conjunction started/ended | Link panel, banner |
| `reset` | Too much was missed to replay | UI reloads everything (section 2) |

Stream status in the top bar: `connecting` → `live` → (`reconnecting`) → `live`.

---

## 4. Sending a command — the whole journey

Example: an operator sends **MOVE 5 m**.

### 4.1 Before sending (nothing is created)

As you edit the form, `CommandConsole` calls **`POST /rovers/{id}/plan-previews`** `{steps:[…]}`:
`MissionPlanner` plays the command forward from Earth's last known state on the real terrain, with
the real specs. Returns the path cell by cell, the end state, and the first step that would fail.
**200**, `no-store`, nothing saved. UI: ghost path on the map, "battery after: 82 %", warnings such
as "rock ahead" or "no sunlight — sunrise in 140 s".

### 4.2 Submit — `POST /api/v1/rovers/1/commands`

```http
POST /api/v1/rovers/1/commands
Authorization: Bearer eyJ…
Idempotency-Key: 6f1c…   (a UUID made once by ApiClient, reused on its own retries)
Content-Type: application/json

{"type":"MOVE","distanceMeters":5}
```

Inside mission-control, in order:

| # | Where | What | Can fail with |
|---|---|---|---|
| 1 | Security filter chain | JWT valid? | 401 |
| 2 | `@PreAuthorize("hasRole('OPERATOR')")` | Role | 403 |
| 3 | `@Valid RoverCommand` | Jackson picks the record by `type` (`Move`), Bean Validation (`distanceMeters` 1–…) | 400 `validation-error` |
| 4 | `IdempotencyService.validateKey` | Key present and well-formed | 400 `invalid-idempotency-key` |
| 5 | `SubmissionRateLimiter` | Token bucket, 30 per minute per user | 429 + `Retry-After` |
| 6 | `CommandService.submit` → `IdempotencyService.execute` | Same user + key seen before? Same body → **replay** the stored response with `Idempotent-Replayed: true`; different body → 422; first request still running → 409 | 422 `idempotency-key-reused`, 409 `idempotency-key-in-progress` |
| 7 | `checkAgainstLastKnownState` | Safe mode → only REBOOT; DRILL needs ≥ 30 % battery; MOVE needs ≥ 1 %/m | 422 `rover-in-safe-mode`, 422 `insufficient-battery` |
| 8 | `new Command(...)` + save | Row in `commands`, status **QUEUED** (or **PENDING_APPROVAL** for DRILL_SAMPLE); trace id stored | |
| 9 | `AuditLog.record` | `COMMAND_SUBMITTED` in `audit_log` | |
| 10 | `RoverEventPublisher.publish` | `command` SSE event | |
| 11 | Response stored for the idempotency key, all in **one transaction** | | |

Response:

```http
HTTP/1.1 202 Accepted
Location: /api/v1/commands/103
Retry-After: 5
{"id":103,"status":"QUEUED","progressPercent":0,"_links":{"self":…,"cancel":…,"rover":…}}
```

**202**, not 201/200: the work is *accepted, not done*. `Location` says where to watch it,
`Retry-After` when polling is worth it. `_links` (HATEOAS) only contains actions that are possible
now — `cancel` disappears once it can't be cancelled.

UI: "Command #103 QUEUED" under the button; a new row in the timeline with a **QUEUED** chip and a
Cancel button.

### 4.3 On Earth: QUEUED → TRANSMITTED (`UplinkTransmitter`, every 500 ms)

In one transaction:
1. `pg_try_advisory_xact_lock`: only one transmitter in the whole cluster, so sequence numbers never
   interleave.
2. `DsnService.uplinkOpen(now)`: if a **conjunction** is on or **no relay window** is open, stop —
   commands simply stay QUEUED (and cancellable).
3. Select `QUEUED` commands older than the 5 s hold, oldest first, `FOR UPDATE SKIP LOCKED`. A
   mission step waits until earlier steps of its mission have left Earth.
4. Per command: next number from `uplink_sequences` (`UPDATE … RETURNING`), `command.transmit()` →
   **TRANSMITTED**, and a `CommandPacket` (new `packetId`, sequence, command, `traceparent`) is
   written to the **outbox** `uplink_packets` with `deliver_at = now + 10 s`.
5. SSE: `command` (TRANSMITTED) and `uplink` (sequence, sent at, arrives at).

UI: chip **TRANSMITTED**, Cancel disappears, "reaches Mars in 10 s" countdown, a pulse travels in 3D.

If you cancel in the same instant, the row lock + `@Version` make one of them lose: a command is
never both cancelled and transmitted.

### 4.4 Earth → Mars: `UplinkDispatcher` (every 500 ms)

1. Claim due packets from `uplink_packets` (`deliver_at` passed) with a **lease** (30 s), so two
   instances never send the same packet at once.
2. **No transaction during HTTP.** `RoverLinkClient` signs the JSON body with `PacketSigner`:
   `X-Link-Signature: t=<epoch>,v1=<HMAC-SHA256("t." + body)>` and POSTs to rover-sim
   `/flight/uplink`.
3. Outcome: 2xx → mark sent. Permanent 4xx → command **FAILED** (`UPLINK_REJECTED`). Anything else
   (timeout, 5xx, rover down) → retry with doubling back-off, **same packetId**.

### 4.5 On Mars: receiving — `POST /flight/uplink` (rover-sim)

1. `LinkSignatureFilter` checks the HMAC over the **raw bytes** in constant time and the timestamp
   (± 5 min). Bad → 401. There are no users on the rover; only the signature counts.
2. `UplinkService.receive`: `INSERT … ON CONFLICT DO NOTHING` into `received_commands` keyed by
   `packetId`.
   - New → stored as waiting, plus a **COMMAND_STATUS RECEIVED** report put in the rover's outbox
     `downlink_packets` (`deliver_at = now + 10 s`), same transaction.
   - Same packet again → **202 `duplicate: true`**, nothing changes (at-least-once delivery made safe).
   - Same packetId with different content, or sequence slot taken by another packet → 409.
3. **202 Accepted** to Earth: stored, not executed.

### 4.6 On Mars: executing — `CommandStepper.advance` (flight loop)

Per rover, per tick, in one transaction, at most one thing:

- **Start the next command**, strictly by `sequenceNo`:
  - lower than expected → **REFUSED** `SEQUENCE_EXPIRED`;
  - a gap (a lower number not yet here) → wait up to the reorder timeout, then skip it with a
    `SEQUENCE_GAP_SKIPPED` warning event;
  - safe mode and not REBOOT → **REFUSED** `SAFE_MODE`;
  - otherwise `FlightRules.plan` → steps (MOVE 5 m = 5 steps of 1 s) → **EXECUTING 0 %** report.
- **Do the next step** when it is due: `FlightRules.step` (pure function) checks the cell ahead —
  rock / map edge / slope > 30° / battery → **FAILED**, and for those hazards **safe mode** + a
  CRITICAL `SAFE_MODE_ENTERED` event. Random events: stuck wheel, dust storm. Otherwise the rover
  state changes and reports go out:
  - `ROVER_STATE` (new x, y, heading, battery…) after every step;
  - `PHOTO` (PNG from `PhotoRenderer`) for TAKE_PHOTO; `SAMPLE` for DRILL_SAMPLE;
  - `COMMAND_STATUS` EXECUTING 20 %, 40 % … then **COMPLETED 100 %**.

Every report is a row in `downlink_packets` with its own `packetId`.

### 4.7 Mars → Earth: `DownlinkDispatcher` → `POST /dsn/downlink`

1. Packets older than the 10 s light time are claimed (lease) and POSTed, HMAC-signed, to
   mission-control `/dsn/downlink`.
2. mission-control: `LinkSignatureFilter` (401 if bad) → `DsnService.requireDownlink()`: during a
   **conjunction → 503 + `Retry-After: 30`**; the rover keeps the packet and waits that long.
3. `TelemetryService.receive`, one transaction:
   - `INSERT INTO telemetry … ON CONFLICT (packet_id) DO NOTHING` → duplicate? answer **202
     `duplicate: true`** and stop.
   - Apply by type:
     - `COMMAND_STATUS` → `Command.applyRoverReport`: **forward only** (a late "EXECUTING 40 %"
       after "COMPLETED" is ignored); final states set `finishedAt`, failure code/detail.
       COMPLETED → webhook `command.completed`; FAILED / REFUSED → `command.failed`.
     - `ROVER_STATE` → `Rover.applyTelemetry` (ignored if older than what Earth has); webhook
       `rover.safe_mode` on entering safe mode, `rover.battery_low` on dropping below 20 %.
     - `EVENT` → SSE `rover-event`. `PHOTO` → `photos` table + SSE `photo`. `SAMPLE` → SSE `sample`.
   - Webhooks are only *queued* here (`webhook_deliveries`), not sent — section 10.
4. **202** to the rover; it marks the packet sent. Network error / 5xx → the rover retries forever
   with back-off up to 60 s (store-and-forward: nothing is lost).

### 4.8 What you see, second by second (MOVE 5 m, defaults)

| t | Status (Earth's view) | Where the truth is | UI |
|---|---|---|---|
| 0 s | QUEUED | Earth DB | Row + Cancel button |
| ~5 s | TRANSMITTED | Packet "in space" | Countdown, pulse to Mars |
| ~15 s | (still TRANSMITTED) | Rover stored it, started driving | Nothing yet — Earth doesn't know |
| ~25 s | RECEIVED → EXECUTING 0 %… | Rover has finished the drive | Progress bar moves, rover moves meter by meter in 3D — 10 s late |
| ~30 s | COMPLETED 100 % | | Green chip, toast, webhook fired |

That lag is the point: **Earth only shows what telemetry told it**.

### 4.9 Command status → UI

| Status | Meaning | UI | Actions |
|---|---|---|---|
| `PENDING_APPROVAL` | DRILL_SAMPLE waiting for a second director | Amber chip, approval inbox | Approve / Reject (directors), Cancel |
| `QUEUED` | On Earth, in the 5 s hold or waiting for the link | Chip | Cancel |
| `TRANSMITTED` | On its way to Mars | Countdown | — |
| `RECEIVED` | Rover stored it | Chip | — |
| `EXECUTING` | Running | Progress bar % | — |
| `COMPLETED` | Done | Green, toast | — |
| `FAILED` | Stopped (obstacle, slope, battery, stuck wheel, storage…) | Red + reason, toast | — |
| `REFUSED_BY_ROVER` | Never started (safe mode, expired sequence) | Red + reason | — |
| `CANCELLED` | Withdrawn on Earth | Grey | — |
| `REJECTED` | A director said no | Grey + reason | — |

---

## 5. Cancelling — `POST /api/v1/commands/{id}/cancel` (OPERATOR)

`Command.cancel`: only PENDING_APPROVAL or QUEUED. Already cancelled → **200** again (safe to retry,
no change). Already TRANSMITTED or later → **409** `command-not-cancellable` ("it's in space, can't
call it back"). Audit `COMMAND_CANCELLED`, SSE `command`; if it was a mission step,
`MissionAborter` cancels the mission's remaining steps. UI follows the `_links.cancel` URL from the
command rather than building it.

## 6. Approval (four-eyes) — DRILL_SAMPLE

1. Operator/director submits a DRILL → **PENDING_APPROVAL** (not sent).
2. A flight director opens the approval inbox: `POST /commands/{id}/approve` or `/reject`
   `{reason?}` (FLIGHT_DIRECTOR).
   - Approving your **own** command → **403** `four-eyes-violation`.
   - Approve re-checks Earth's latest knowledge (battery may have dropped meanwhile) → 422.
   - Already approved/rejected the same way → **200** again; the opposite decision → **409**
     `command-not-pending-approval`.
3. Approved → QUEUED (5 s hold counted from now) → continues as section 4. Rejected → REJECTED.
   Audit records who decided.

With one director account you can't approve your own drill — a real mission needs two people.

## 7. Missions, routes and click-to-drive

| Action | Call | Server | UI |
|---|---|---|---|
| Click a cell on the map | `POST /rovers/{id}/routes` `{x, y}` (VIEWER) | `RoutePlanner` (A*) around rocks and steep slopes from the last known position → ROTATE/MOVE steps + preview. Unreachable → 422 `no-route` | Route panel with the path drawn |
| "Drive there" | `POST /rovers/{id}/missions` (OPERATOR, Idempotency-Key) | `MissionService.submit`: rover busy → **409** `rover-busy` (lists unfinished commands); `MissionPlanner` simulates all steps → first impossible step → **422** `mission-plan-infeasible`; else creates a `Mission` + one `Command` per step (`mission_id`, `mission_step`), all QUEUED (drills PENDING_APPROVAL) → **202** + `Location: /missions/{id}` | Missions panel with progress |
| Build a plan in the console | same endpoint with your steps (max 20) | same | same |
| Mission progresses | — | Steps are transmitted in order; status derived from steps (`MissionStatus.of`): any FAILED/REFUSED/REJECTED → failed, any CANCELLED → cancelled, all COMPLETED → completed | Progress bar *n/total* |
| Cancel mission | `POST /missions/{id}/cancel` | Cancels every step still on Earth; nothing left on Earth → 409 | Steps go CANCELLED |
| A step fails | — | `MissionAborter` (on `CommandEnded`) cancels the remaining steps on Earth | Mission shows failed |

## 8. The link: relay windows and solar conjunction

- `GET /link` → light time, whether a relay window is open and when it next changes, conjunction on
  or off. `no-cache` and no ETag (it depends on the clock).
- Director toggles conjunction in the Link panel:
  1. `GET /link/conjunction` → state + **ETag**.
  2. `PUT /link/conjunction` `{active:true}` with `If-Match: <etag>` → `LinkState` updated, audit,
     SSE `link`. Same value again → 200, no change (idempotent PUT). Someone changed it meanwhile
     → **412**.
- While active: the uplink transmitter sends nothing (commands stay QUEUED, still cancellable); the
  downlink answers the rover **503 + Retry-After: 30**; the rover holds its packets. When it ends,
  everything flows again, nothing lost.
- Relay windows (`redlink.dsn.windows.enabled`, off by default): the uplink only transmits during a
  45 s window every 2 min — the orbiter pass.

## 9. Photos and telemetry history

- TAKE_PHOTO runs like any command (section 4); the PHOTO telemetry carries the PNG as base64.
  `PhotoService.store` writes it to `photos` (bytea); SSE `photo`.
- `GET /rovers/{id}/photos` → metadata page. `GET /photos/{id}`: `Accept: application/json` →
  metadata, `Accept: image/png` → bytes (two methods on one URL, chosen by `produces`).
- `GET /rovers/{id}/telemetry?type=&from=&to=&page=` → raw history (charts, drill marks). Empty
  time range → 400.

## 10. Webhooks

| Action | Call (VIEWER, own webhooks only) | Server |
|---|---|---|
| Register | `POST /webhooks` `{url, events}` | `WebhookUrlPolicy` (SSRF: https + public host outside `local`) and event names → 400 if refused; too many webhooks for you → 422; random secret; **201** + `Location`; the secret is in this response **only** |
| List / get | `GET /webhooks`, `GET /webhooks/{id}` | No secret in these |
| Test | `POST /webhooks/{id}/ping` | Queues a `ping` delivery → 202 |
| History | `GET /webhooks/{id}/deliveries` | Attempts, status codes, errors |
| Remove | `DELETE /webhooks/{id}` | **204**; deliveries cascade |

Delivery (`WebhookDispatcher`, scheduled): claim due rows from `webhook_deliveries` → `WebhookSender`
POSTs the JSON with `X-RedLink-Event`, `X-RedLink-Delivery` (same on retries), `X-RedLink-Signature`
(HMAC with the webhook's secret), redirects not followed. 2xx → delivered. **410** → webhook
deactivated. Other → retry 2 s, 4 s, 8 s … (max 5 min), give up after 8 attempts.

## 11. Audit log — `GET /api/v1/audit` (FLIGHT_DIRECTOR)

Paged list of who did what (`COMMAND_SUBMITTED`, `APPROVED`, `REJECTED`, `CANCELLED`, conjunction
changes, …). Paging links come in the RFC 8288 **`Link`** header (`rel="next"`, …), which
`AuditDialog` follows.

## 12. Not used by the UI, but in the API

- `PATCH /rovers/{id}` (`application/merge-patch+json`, OPERATOR): rename a rover. **Requires
  `If-Match`**: missing → **428**, stale → **412**. Shown in Swagger / tests, not in the UI.
- `GET /commands/{id}`, `GET /missions/{id}` with `If-None-Match` → **304 Not Modified**.
- Swagger UI: `http://localhost:8080/swagger-ui.html` lists everything above.

---

## Status codes at a glance

| Code | When |
|---|---|
| 200 | Reads; repeated idempotent actions (cancel/approve/reject/PUT again) |
| 201 | Webhook created, stream ticket issued |
| 202 | Command or mission accepted (async); link packets accepted (also duplicates) |
| 204 | Webhook deleted |
| 304 | `If-None-Match` matches the ETag |
| 400 | Validation failed, bad Idempotency-Key, malformed JSON, refused webhook URL |
| 401 | No/expired token, wrong password, bad stream ticket, bad link signature |
| 403 | Role too low; approving your own command (four-eyes) |
| 404 | Unknown rover / command / mission / photo; a webhook that is not yours |
| 409 | Cancel after transmission; decision on a non-pending command; rover busy for a mission; same Idempotency-Key still in progress |
| 412 | `If-Match` is stale (someone changed it first) |
| 422 | Safe mode, insufficient battery, infeasible plan, no route, Idempotency-Key reused with another body, webhook limit reached |
| 428 | `If-Match` missing where required |
| 429 | Rate limit (30/min) or login throttle, with `Retry-After` |
| 503 | Solar conjunction on the downlink (`Retry-After`), too many SSE streams |
