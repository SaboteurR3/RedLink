# RedLink — Architecture

> An asynchronous REST platform for commanding a simulated Mars rover over a delayed deep-space
> link. Work is delivered ticket by ticket: see `tickets/` and the index in `README.md`.

A simulated Mars rover mission. Operators on Earth command rovers through a REST API; commands
travel over a simulated deep-space link with real delay, a separate rover program executes them,
and results come back as delayed telemetry. The frontend is a 3D "mission control" scene.

Real-world framing: a **remote device command-and-control platform** (drones, IoT, EV chargers,
satellites). Mars is the extreme case: unavoidable delay, unreliable link, and a duplicate command
can destroy the machine.

---

## 1. System overview

```
┌──────────────────────┐      REST + SSE       ┌──────────────────────────────┐
│  Frontend (3D)       │ ◄───────────────────► │  mission-control  (Earth)    │
│  React + R3F         │                       │  Spring Boot  :8080          │
└──────────────────────┘                       │  public REST API, auth,      │
                                               │  approvals, webhooks, SSE    │
                                               │  + DSN component (link)      │
                                               └──────────┬───────────────────┘
                                                 uplink   │ ▲  downlink
                                          (delayed, HMAC) │ │  (delayed, HMAC)
                                                          ▼ │
                                               ┌──────────────────────────────┐
                                               │  rover-sim  (Mars)           │
                                               │  Spring Boot  :8081          │
                                               │  "flight software": validates│
                                               │  executes, safe mode,        │
                                               │  records telemetry           │
                                               └──────────────────────────────┘

        PostgreSQL (one container, two databases: mission_control, rover)
```

Key rule: **mission-control and rover-sim share only the contract** (command dictionary +
telemetry dictionary), never code paths or database tables — exactly like a real mission, where
Earth software and flight software share only the dictionaries.

**Earth only knows the past.** mission-control never reads the rover's true state; it only knows
what telemetry has told it. The UI shows "last known state, 10 s old".

---

## 2. Maven layout

```
redlink/                         monorepo (backend + frontend in one repository)
├── pom.xml                      redlink-pom (parent, packaging=pom)
├── redlink-contract/            plain jar, no Spring Boot app
│   ├── command: CommandType, RoverCommand (sealed: Move, Rotate, ...), CommandPacket
│   ├── telemetry: TelemetryType, TelemetryPayload (sealed), TelemetryPacket
│   ├── link: PacketSigner (HMAC-SHA256)
│   └── terrain: Terrain, TerrainLoader (heightmap + obstacle mask)
├── redlink-mission-control/     Spring Boot app :8080 (Earth)
├── redlink-rover-sim/           Spring Boot app :8081 (Mars)
├── frontend/                    React + Vite + TypeScript + React Three Fiber
├── docker/                      compose support files (Postgres init script)
├── docs/                        specs/redlink (this spec + tickets), adr/
└── docker-compose.yml
```

**Stack:** Java 17, Spring Boot 4.1, Maven (wrapper), PostgreSQL 18.

Module dependencies:
- **redlink-mission-control:** webmvc, data-jpa, PostgreSQL driver, flyway, validation,
  security + oauth2-resource-server, actuator, springdoc-openapi; tests with Testcontainers.
- **redlink-rover-sim:** webmvc, data-jpa, PostgreSQL driver, flyway, validation, security,
  actuator; tests with Testcontainers.

---

## 3. The contract (redlink-contract)

Packages: `contract.command` (command dictionary, `CommandPacket`), `contract.telemetry`
(telemetry dictionary, `TelemetryPacket`), `contract.rover` (shared value types),
`contract.link` (`PacketSigner`). Both dictionaries are sealed interfaces, so adding an entry is a
compile error in every `switch` that forgets it, on both sides of the link.

### Command dictionary (`CommandType` + sealed `RoverCommand`)

| Type | Params | Duration (sim) | Fails when |
|---|---|---|---|
| `MOVE` | `distanceMeters` (1–20) | 1 s per meter | obstacle, slope too steep, battery runs out |
| `ROTATE` | `heading` NORTH/EAST/SOUTH/WEST | 2 s | — |
| `TAKE_PHOTO` | `camera` NAVCAM/MASTCAM | 3 s | storage full |
| `DRILL_SAMPLE` | — | 15 s | battery < 30 %, terrain is rock; **needs approval** |
| `CHARGE` | `minutes` (1–120) | scaled | — (a dust storm cuts the gain to ¼) |
| `REBOOT` | — | 5 s | — (only command accepted in SAFE_MODE) |

The type travels **inside** the command object, so a packet can never say `MOVE` with `ROTATE`
parameters.

### CommandPacket (uplink, Earth → Mars)
```json
{
  "packetId": "7f3c1d2e-0000-4000-8000-0000000000e1",   // dedupe key on the rover
  "roverId": 1,
  "commandId": 981,              // Earth-side id, echoed back in COMMAND_STATUS telemetry
  "sequenceNo": 17,              // per-rover order; rover executes in this order
  "command": { "type": "MOVE", "distanceMeters": 5 },
  "sentAt": "2026-10-26T12:00:00Z"
}
```

### Telemetry dictionary (`TelemetryType` + sealed `TelemetryPayload`)

| Type | Fields |
|---|---|
| `COMMAND_STATUS` | `commandId`, `status` RECEIVED/EXECUTING/COMPLETED/FAILED/REFUSED, `progressPercent`, `failureCode`?, `failureDetail`? |
| `ROVER_STATE` | `x`, `y`, `heading`, `batteryPercent`, `temperatureCelsius`, `storageUsedPercent`, `mode` NOMINAL/SAFE_MODE |
| `EVENT` | `code`, `severity` INFO/WARNING/CRITICAL, `message` |
| `PHOTO` | `commandId`, `camera`, `x`, `y`, `heading`, `imageBase64` (PNG, ≤ 2 MB base64; Earth keeps the image in `photos`, telemetry history keeps the rest) |

### TelemetryPacket (downlink, Mars → Earth)
```json
{
  "packetId": "uuid",            // dedupe key on Earth
  "roverId": 1,
  "roverTime": "2026-10-26T12:00:10Z",   // when it happened on Mars
  "payload": { "type": "COMMAND_STATUS", "commandId": 981, "status": "COMPLETED", "progressPercent": 100 }
}
```

### Link authentication (`PacketSigner`)

Every packet in both directions carries
`X-Link-Signature: t=<epoch seconds>,v1=<base64url HMAC-SHA256 of "<t>." + raw body>`.
- Shared secret of at least 32 bytes, from configuration (never in the repository).
- The receiver verifies the **raw body bytes** before parsing, compares in constant time, and
  rejects timestamps outside the tolerance window (both directions).
- Replays inside the window are dropped by packet-id dedupe.
- Every failure gets the same `401` answer; the reason is only logged.

Real missions authenticate uplinked commands the same way, so nobody else can drive the rover.

---

## 4. The deep-space link (DSN component, inside mission-control)

Both directions use the **transactional outbox pattern on PostgreSQL** = store-and-forward, like
NASA's Delay/Disruption Tolerant Networking.

**Uplink**
1. A command waits `QUEUED` for `queue-hold` (5 s): the last chance to cancel.
2. `UplinkTransmitter` (every 500 ms, exclusive via `pg_try_advisory_xact_lock`) then, in **one
   transaction**: takes the next per-rover sequence number (`uplink_sequences`), marks the command
   `TRANSMITTED`, and writes an `uplink_packets` row with `deliver_at = now + oneWayDelay`.
   Numbers are assigned at transmission, in creation order, so cancelled commands leave no gaps.
   A mission step is only taken once every earlier step of its mission has left Earth (or ended),
   so a step waiting for approval holds back the rest of the plan.
3. `UplinkDispatcher` claims due packets with a lease (`FOR UPDATE SKIP LOCKED`), POSTs them
   signed to `rover-sim /flight/uplink` outside any transaction, and records the outcome — same
   design as the downlink below. Failure (rover-sim down = "lost signal") → retry with backoff.
   400/409/413/422 → packet `REJECTED`, command `FAILED` (`UPLINK_REJECTED`).
4. Delivery is **at-least-once**; rover-sim dedupes on `packetId` → **effectively once**.
5. The rover's HTTP 202 does not change the command's status: Earth learns RECEIVED only from
   telemetry.

**Downlink** — mirror image inside rover-sim: telemetry rows in `downlink_packets` with
`deliver_at = createdAt + oneWayDelay`, a dispatcher POSTs them to
`mission-control /dsn/downlink`, mission-control dedupes on `packetId`.

Dispatcher design (implemented in ticket 07, reused for the uplink in ticket 10):
1. **Claim with a lease**, one statement: `UPDATE ... SET next_attempt_at = now + lease,
   attempts = attempts + 1 WHERE packet_id IN (SELECT ... WHERE status='PENDING' AND
   next_attempt_at <= now ... FOR UPDATE SKIP LOCKED) RETURNING ...`. Concurrent dispatchers get
   disjoint packets.
2. **Send outside any transaction** (signed, HTTP/1.1, connect/read timeouts), so a slow receiver
   never holds database connections or row locks.
3. **Record the outcome**: 2xx → `SENT`; 400/409/413/422 → `REJECTED` (never resent); anything
   else (network error, 5xx, 404 while the receiver deploys, 401 during secret rotation) → retry
   after 1 s, 2 s, 4 s … capped at 60 s, forever.
4. A dispatcher that dies after claiming leaves the packet reserved only until the lease ends;
   then it is sent again. Together with receiver-side dedupe: effectively once.

**Configuration** (mission-control)
```yaml
redlink:
  uplink:
    one-way-delay: 10s        # real Mars: 3–22 min
  dsn:
    windows:                  # relay-orbiter passes, repeating from the Unix epoch
      enabled: false
      every: 2m
      duration: 45s
    conjunction-retry-after: 30s
```

**Relay windows (ticket 19):** with windows enabled, the transmitter only takes commands while a
window is open; outside one they stay QUEUED (still cancellable). The schedule is a pure function of
the clock (`LinkWindows`), so every instance agrees without coordination.

**Solar conjunction (ticket 19):** `PUT /api/v1/link/conjunction {active}` (FLIGHT_DIRECTOR;
idempotent; optional `If-Match` → 412; ETag = `link_state.version`), `GET /api/v1/link/conjunction`
(ETag, 304). While active:
- commands are accepted but stay on Earth;
- `/dsn/downlink` answers `503 link-unavailable` with `Retry-After`;
- the rover's dispatcher waits `max(backoff, Retry-After)` (delta-seconds or HTTP-date, capped at
  1 h) and does not try the rest of that batch.

The change is pushed as SSE `link` to every rover's stream. `GET /api/v1/link` (VIEWER, `no-cache`,
no ETag because it depends on the clock) shows the light time, the window (open, next change), the
conjunction and `uplinkOpen`.

The UI could also show the real current Earth–Mars light time next to the simulated one (not built).

---

## 5. Command lifecycle (Earth view)

```
PENDING_APPROVAL ──approve──► QUEUED ──dispatcher──► TRANSMITTED ──(telemetry)──► RECEIVED
      │ reject                  │ cancel                                            │
      ▼                         ▼                                                   ▼
   REJECTED                 CANCELLED                                          EXECUTING
                                                                                    │
                                                         COMPLETED ◄────────────────┤
                                                         FAILED    ◄────────────────┤
                                                         REFUSED_BY_ROVER ◄─────────┘ (safe mode,
                                                                                bad signature, invalid)
```

- Everything after `TRANSMITTED` is learned **only from telemetry** (delayed), received on
  `POST /dsn/downlink` (signed; dedupe on `packet_id`; duplicate → `202 {duplicate:true}`;
  unknown rover → `422` so the rover does not retry).
- Telemetry may repeat and arrive out of order, so a command's status only moves **forward**:
  `TRANSMITTED < RECEIVED < EXECUTING (progress only rises) < final`; final states never change;
  `finishedAt` is the Mars time. `ROVER_STATE` replaces the rover's last known state only if its
  Mars time is not older than what Earth has (the ETag changes with it).
- Cancel is allowed only in `PENDING_APPROVAL` / `QUEUED`; after that → `409 Conflict`
  ("command already in space").
- Status changes go out through SSE and webhooks.

---

## 6. rover-sim ("flight software")

- `POST /flight/uplink` — verify HMAC, validate against the dictionary, dedupe on `packetId`,
  store in `received_commands`, return `202 {packetId, commandId, duplicate, receivedAt}`.
  Emits `COMMAND_STATUS RECEIVED` telemetry in the same transaction.
  - Repeated packet → `202` with `duplicate: true`, no effect (race-free `INSERT ... ON CONFLICT DO NOTHING`).
  - Same packet id, different content → `409 packet-conflict`; sequence slot taken → `409 sequence-conflict`.
  - Unknown rover → `404 unknown-rover`; not a valid dictionary command → `400 invalid-packet`.
  - Every 4xx is permanent: Earth must not retry it. Only network errors and 5xx are retried.
- **Executor** (`RoverExecutor`, fixed delay 250 ms): per rover, one transaction
  (`CommandStepper`) either starts the next command or performs the current command's next step
  when it is due. Rules are pure (`FlightRules`): MOVE one cell per second (−1 % ground, −2 %
  sand), ROTATE 90° per 2 s the shortest way, TAKE_PHOTO 3 s, DRILL_SAMPLE 15 s (needs ≥ 30 %),
  CHARGE +1 % per simulated minute, REBOOT 5 s. Max slope 30°.
- **Ordering:** strictly by `sequenceNo` (`rover_state.last_sequence_no`). A missing number is
  waited for up to `reorder-timeout` (30 s), then skipped (EVENT `SEQUENCE_GAP_SKIPPED`); if it
  arrives later it is REFUSED `SEQUENCE_EXPIRED`.
- **Telemetry:** COMMAND_STATUS on start, every step (progress %) and the end; ROVER_STATE after
  every change (drives the 3D animation later); EVENTs for safe mode and skipped gaps.
- **Fault protection:** hazards (OBSTACLE, SLOPE_TOO_STEEP, BATTERY_EXHAUSTED) → stop, mode
  `SAFE_MODE`, refuse everything except `REBOOT` (`SAFE_MODE`). Resource problems (STORAGE_FULL,
  LOW_BATTERY) only fail the command.
- **Truth:** the rover's real state lives only here (`rover_state`), with `@Version` optimistic
  locking.
- **Photos:** `PhotoRenderer` draws a first-person voxel-space view with Java2D from the terrain
  (1.8 m mast; NAVCAM 90° grayscale, MASTCAM 40° colour; 320×240), deterministic, no fonts. Sent
  as a `PHOTO` telemetry packet (base64 PNG) in the TAKE_PHOTO step's transaction, before `COMPLETED`.
- **Random events (ticket 19, `redlink.rover.events`, all dice through a `Dice` bean):** dust storms
  (weather check every `weather-interval`; `rover_state.dust_storm_until`; EVENT `DUST_STORM_STARTED`
  WARNING / `DUST_STORM_ENDED` INFO; charging at `RoverSpecs.DUST_STORM_CHARGE_FACTOR`), stuck wheel
  (each meter of a MOVE may fail `WHEEL_STUCK`, no safe mode). `FlightRules.step` gets the outcome
  as `Conditions`, so the rules stay pure. Earth's `MissionPlanner` plans nominally.

---

## 7. Terrain

- One folder per site, `redlink-contract/src/main/resources/sites/<siteId>/`:
  - `site.properties`: id, name, grid size, meters per cell, elevation range, rover start cell.
  - `heightmap.png`: 8-bit grayscale, 0 = `minElevationMeters`, 255 = `maxElevationMeters`.
  - `surface.png`: 8-bit grayscale, 0 = ground, 128 = sand (more battery), 255 = rock (blocked).
  - Row 0 is the north edge; one cell = one meter. The map edge counts as blocked.
- `TerrainLoader` (classpath or folder, validated; site ids are restricted to `[a-z0-9-]` so
  they cannot escape the sites folder), immutable `Terrain` (`elevationAt`, `surfaceAt`,
  `isBlocked`, `slopeDegrees`), `TerrainWriter`, `TerrainGenerator`.
- Default site `jezero-sim`: 64 × 64 m, 0–4 m elevation, start (32, 32), seed 2021. Generated
  with value noise, 3–5 craters with raised rims (steep walls are the hazards), sand patches and
  rocks (denser on crater rims, placed by hashing (seed, x, y)), plus a flat, rock-free landing zone.
  A test fails if the committed files and the generator drift apart.
- Later the site can be replaced with real NASA elevation data (e.g. a HiRISE DTM of Jezero crater
  converted to the same files); nothing else changes.
- rover-sim uses it for collision and slope checks; mission-control serves it to the frontend
  (`GET /api/v1/sites/{id}/heightmap`), and the 3D scene builds its terrain mesh from the same
  file → backend and 3D world always match.

---

## 8. Public REST API (mission-control)

All under `/api/v1`. JSON, errors as **RFC 9457 Problem Details**, docs via OpenAPI/Swagger UI.

### Auth
| Method | Path | Notes |
|---|---|---|
| POST | `/auth/token` | username/password → JWT (roles VIEWER, OPERATOR, FLIGHT_DIRECTOR) |

### Rovers
| Method | Path | Notes |
|---|---|---|
| GET | `/rovers?page=&size=` | VIEWER; last known state; `{content, page{size,number,totalElements,totalPages}}`, size ≤ 100, fixed sort by id |
| GET | `/rovers/{id}` | VIEWER; `ETag: "<version>"`, `Cache-Control: no-cache, private`; `If-None-Match` → `304` |
| PATCH | `/rovers/{id}` | OPERATOR; JSON Merge Patch (`application/merge-patch+json`, else 415), only `name`; `If-Match` required: missing → `428`, stale/weak → `412`, `*` allowed; concurrent writers → one wins, rest `412` |

### Sites
| Method | Path | Notes |
|---|---|---|
| GET | `/sites/{siteId}` | VIEWER; grid, elevation range, start cell |
| GET | `/sites/{siteId}/heightmap`, `/surface` | `image/png`, content-hash `ETag`, `Cache-Control: max-age=86400, private`, `If-None-Match` → `304` |

API documentation: `/v3/api-docs` (OpenAPI 3.1) and `/swagger-ui.html`, public, bearer scheme.
| POST | `/rovers/{id}/events/tickets` | VIEWER; `201 {ticket, expiresAt, streamUrl}`, `no-store`; single-use, 30 s, bound to the rover (EventSource cannot send headers) |
| GET | `/rovers/{id}/events` | VIEWER (`?ticket=` or bearer); **SSE**: `rover` (snapshot first, then changes), `command`, `uplink` (`arrivesAt` for the beam animation), `rover-event`, `photo` (metadata), `reset`; `id:` per event, resume with `Last-Event-ID` / `?lastEventId=`; events stored with the change, pushed after commit; heartbeat 15 s; 503 when full |
| GET | `/rovers/{id}/telemetry?type=&from=&to=&page=&size=` | VIEWER; history newest first by Mars time; `[from, to)`; inverted range → 400 |

### Commands
| Method | Path | Notes |
|---|---|---|
| POST | `/rovers/{id}/commands` | OPERATOR; body = dictionary command (`{"type":"MOVE","distanceMeters":5}`); **`Idempotency-Key` required** → `202 Accepted`, `Location`, `Retry-After: 5`, body with `_links`; DRILL → `PENDING_APPROVAL`; `422 rover-in-safe-mode` / `insufficient-battery` by last known state |
| GET | `/rovers/{id}/commands?status=&page=&size=` | VIEWER; newest first |
| GET | `/commands/{id}` | VIEWER; `ETag`, 304; HATEOAS `_links`: `self`, `rover`, `cancel` (only while allowed) |
| POST | `/commands/{id}/cancel` | OPERATOR; 200 (again 200 if already cancelled); `409 command-not-cancellable` once transmitted |
| POST | `/commands/{id}/approve` · `/reject` (`{reason}` optional, ≤ 500) | FLIGHT_DIRECTOR; **four-eyes**: approving your own command → `403 four-eyes-violation`; repeating a decision → 200, contradicting it → `409 command-not-pending-approval`; approval re-checks last known state (422); after approval the 5 s hold counts from the approval (`queued_at`); `approval {state, decidedBy, decidedAt, reason}` in the command; `_links.approve`/`reject` while pending |

### Missions (batch)
| Method | Path | Notes |
|---|---|---|
| POST | `/rovers/{id}/missions` | OPERATOR; `{name, steps[1..50]}`; **`Idempotency-Key` required** → `202`, `Location`, `Retry-After`; whole plan simulated first from the last known state on the terrain with the contract's `RoverSpecs` (`MissionPlanner`): first impossible step → `422 mission-plan-infeasible` (`step`, `errors`), nothing created; unfinished commands → `409 rover-busy` (plan from a known state); body has the `forecast` end state |
| GET | `/rovers/{id}/missions?page=&size=` | VIEWER; newest first, with steps |
| GET | `/missions/{id}` | VIEWER; `status` derived from the steps (FAILED > CANCELLED > COMPLETED > IN_PROGRESS > QUEUED, never stored), `progress`, `forecast`, `steps`, `_links.cancel` while a step is on Earth; ETag from the steps' versions, 304 |
| POST | `/missions/{id}/cancel` | OPERATOR; withdraws every step still on Earth; 200 (again 200); `409 mission-not-cancellable` when all have left Earth |

A step that ends FAILED, REFUSED_BY_ROVER, REJECTED or CANCELLED withdraws the later steps still on Earth (CANCELLED, `MISSION_ABORTED`), in the same transaction (`CommandEnded` Spring event). Commands carry `mission {id, step}` and `_links.mission`.

### Photos
| Method | Path | Notes |
|---|---|---|
| GET | `/rovers/{id}/photos?page=&size=` | VIEWER; metadata, newest first by Mars time; never the image; unknown rover → 404 |
| GET | `/photos/{id}` | VIEWER; **content negotiation**: `application/json` (also `*/*` or no Accept) → metadata with `_links` (`image` = same URL + `type: image/png`), `no-cache`; `image/png` → bytes, `Content-Disposition: inline`, `max-age=1y, immutable`; other → 406; `Vary: Accept`; one strong ETag per representation (from the PNG’s SHA-256) → 304; unknown → `404 photo-not-found` |

### Webhooks
| Method | Path | Notes |
|---|---|---|
| POST | `/webhooks` | any role; `{url, events[]}` (`command.completed`, `command.failed`, `rover.safe_mode`, `rover.battery_low`) → `201`, `Location`, **secret shown once**, `no-store`; max 10 per user (`422 webhook-limit`); SSRF rules → 400 |
| GET · DELETE | `/webhooks`, `/webhooks/{id}` | own webhooks only (others → `404`); never the secret; DELETE `204`, drops pending deliveries |
| GET | `/webhooks/{id}/deliveries` | delivery log: status, attempts, last HTTP status / error |
| POST | `/webhooks/{id}/ping` | `202`, sends a `ping` delivery |

Deliveries: body `{eventId, event, occurredAt, data}`; headers `X-RedLink-Signature: t=…,v1=…`
(HMAC-SHA256 of `t.body` with the webhook's secret, same scheme as the link), `X-RedLink-Event`,
`X-RedLink-Delivery` (dedupe id). Outbox rows are fanned out in the transaction of the change;
the dispatcher claims with a lease, retries 2 s … 5 min up to 8 attempts, then `FAILED`;
`410 Gone` deactivates the webhook. **SSRF protection**: https only, no credentials, every resolved
address must be public (no loopback, private, link-local/metadata, CGNAT, IPv6 ULA, …), checked at
registration and again before each delivery; no redirects; 3 s / 5 s timeouts.

### Idempotency rules
- Missing or malformed key (1–255 visible ASCII) → `400 invalid-idempotency-key`.
- Same user + same key + same request (rover + canonical JSON) → replay the stored response
  byte-for-byte (`TEXT`, not `jsonb`), with `Idempotent-Replayed: true`.
- Same key, different request → `422 idempotency-key-reused`. Keys are per user.
- Key row inserted (`ON CONFLICT DO NOTHING`) in the same transaction as the command: concurrent
  duplicates wait on its lock and replay; failures roll the key back (only successes are replayed).
- Key looked up before the state pre-checks, so a successful request still replays after the
  rover's state changed.
- Records expire after 24 h (key reusable); an hourly job purges them.

### Example error
```json
{
  "type": "https://mission-control/problems/insufficient-battery",
  "title": "Insufficient battery",
  "status": 422,
  "detail": "MOVE 5 needs 25% battery, last known level is 12%",
  "roverId": 1
}
```

---

## 9. Database

Flyway migrations in each service. `jsonb` for params/payloads.

### mission_control database
| Table | Key columns |
|---|---|
| `users` | id, username (unique), password_hash, role |
| `rovers` | id, name, site_id, last_x, last_y, last_heading, last_battery, last_mode, last_telemetry_at, version |
| `commands` | id, rover_id, mission_id, sequence_no, type, params jsonb, status, created_by, approved_by, failure_code, failure_detail, created_at, transmitted_at, completed_at, version — unique (rover_id, sequence_no) |
| `missions` | id, rover_id, name, created_by, created_at, forecast jsonb, version (status derived from steps); `commands.mission_id`/`mission_step`, unique (mission_id, mission_step) |
| `idempotency_records` | user_id, key, request_hash, response_status, response_body jsonb, created_at — PK (user_id, key) |
| `uplink_packets` | id (packetId), command_id, payload jsonb, status, deliver_at, sent_at, attempts, next_attempt_at — partial index on due PENDING rows |
| `telemetry` | id, packet_id (unique), rover_id, type, command_id, payload jsonb, rover_time, received_at — index (rover_id, received_at) |
| `photos` | id (= PHOTO packet id), rover_id, command_id, camera, x, y, heading, width, height, size_bytes, sha256, image bytea, taken_at, received_at |
| `webhooks` | id, user_id, url, secret, events text[], active |
| `webhook_deliveries` | id, webhook_id, event, payload jsonb, status, attempts, next_attempt_at, last_status_code |
| `link_state` | single row: conjunction_active, changed_by, changed_at, version |

### rover database
| Table | Key columns |
|---|---|
| `rover_state` | rover_id, name, site_id, x, y, heading, battery_percent, temperature_celsius, storage_used_percent, mode, version — rover 1 seeded at the site start |
| `received_commands` | packet_id (PK = dedupe), rover_id, command_id, sequence_no, type, command jsonb, status, progress_percent, sent_at, received_at, started_at, finished_at, failure_code, failure_detail — unique (rover_id, sequence_no); partial index on open commands |
| `downlink_packets` | packet_id (PK), rover_id, type, packet jsonb (whole TelemetryPacket), status PENDING/SENT, created_at, deliver_at, attempts, next_attempt_at, sent_at, last_error — partial index on due PENDING rows |

---

## 10. Frontend

- React + Vite + TypeScript, **React Three Fiber** + **drei** + **@react-three/postprocessing**,
  GSAP for camera flights.
- TypeScript API client generated from the OpenAPI spec.
- The 3D scene **only moves on SSE events** — the backend stays the source of truth.
- Scenes: intro flight Earth → Mars → landing site; terrain mesh from the heightmap; NASA rover
  glTF model; signal beam animated with the real `arrivesAt` time; dust particles, sol day/night;
  HUD (battery, mode, command timeline, alerts); camera modes (follow, top-down, rover cam).
- Build a plain 2D map first, then swap it for the 3D scene.
- **Built (ticket 18):** terrain mesh from the site files (one vertex per cell, ×1.5 relief), boulders
  on rock cells, crater rim; a procedural rover (no external model) that glides to each reported
  cell and turns the short way; a safe-mode beacon; uplink beams timed by `transmittedAt`/`arrivesAt`;
  photo pins; a ghost rover at the planned mission's forecast; fog, low western sun, dust; orbit,
  follow and top-down cameras with a short landing flight. 2D/3D toggle with a WebGL fallback;
  three.js is a lazy chunk. Not built: the Earth→Mars intro, the NASA glTF, post-processing, GSAP,
  sol day/night.

---

## 11. Deployment

`docker-compose.yml` (ticket 20): `postgres` (init script creates `mission_control` and `rover`
databases and roles), `mission-control` (profile `demo`), `rover-sim`, `frontend` (nginx). One
command: `docker compose up --build` → <http://localhost:8088>. Multi-stage images built inside Docker
(JDK build → JRE runtime with layered jars, non-root; Node build → nginx). Only the frontend is
published; nginx is the single origin (SPA + reverse proxy for `/api`, `/v3/api-docs`, `/swagger-ui`;
SSE unbuffered). Secrets from `.env` (`${VAR:?}`); the `demo` profile seeds the three users from
`REDLINK_DEMO_USERS_PASSWORD` and refuses to start without it. `scripts/e2e.mjs` checks a running
system end to end through its front door.

Single instance of each service. (Scaling SSE across instances could use PostgreSQL
`LISTEN/NOTIFY` — mention in the write-up as future work.)

---

## 12. Testing

- Unit: rover executor (move/collision/battery), lifecycle transitions, idempotency hashing,
  HMAC signing.
- Integration (Testcontainers PostgreSQL): idempotent replay and `422`, cancel `409`, `ETag`
  `304`/`412`, outbox dispatch + dedupe, approval flow, Problem Details shapes.
- End-to-end: both apps + Postgres, short delay (1 s), send MOVE, await COMPLETED telemetry.

---

## 13. Build order

| Week | Goal |
|---|---|
| 1 | Parent pom + 3 modules, docker-compose Postgres, Flyway, contract module, terrain loader |
| 2 | rover-sim: uplink endpoint, executor, rover_state, downlink outbox |
| 3 | mission-control: commands API (202, Idempotency-Key), uplink outbox + dispatcher, downlink receiver |
| 4 | Lifecycle from telemetry, cancel/409, Problem Details, SSE, ETag/If-Match |
| 5 | Frontend v1 (2D map, console, timeline) |
| 6 | JWT roles, approvals, webhooks |
| 7 | 3D scene, photos, missions, safe mode, random events, link windows |
| 8 | Tests, docker compose polish, write-up, demo rehearsal |

---

## 14. Write-up chapters (each pattern has a reason to exist)

1. Synchronous vs asynchronous REST — `202 Accepted`, status resources, `Retry-After`.
2. Idempotency — safe retries of `POST`, at-least-once delivery + idempotent receivers.
3. Transactional outbox and store-and-forward (relation to DTN).
4. Concurrency control — `ETag`/`If-Match` vs optimistic locking vs `SKIP LOCKED` queues.
5. Push vs pull — polling, SSE, WebSocket, webhooks.
6. HATEOAS — links as the set of currently allowed actions.
7. Contracts — command/telemetry dictionaries vs OpenAPI.
8. Security — JWT roles, HMAC-signed link packets and webhooks.
9. Error design — RFC 9457 Problem Details.
