# How RedLink works — a guide for backend developers

This document explains **how the code works**: modules, packages, the path of a request, the
database, and where each web-service technique lives. It is written for a Spring Boot developer:
the backend in depth, the frontend only as an overview.

- What the product does for its users, and how to click through it: [GUIDE.md](GUIDE.md).
- Every API call as a flow: what each button sends, what the server does class by class, and what the UI shows at each step: [API-FLOWS.md](API-FLOWS.md).
- The design chapters and limits: [writeup/WRITEUP.md](writeup/WRITEUP.md); a 15-minute demo script: [writeup/DEMO.md](writeup/DEMO.md).
- The formal specification (every endpoint, table and rule): [specs/redlink/SPEC.md](specs/redlink/SPEC.md).
- Why things were designed a certain way: [adr/](adr/).
- What was built when: [specs/redlink/tickets/](specs/redlink/tickets/) (index in [README.md](specs/redlink/README.md)).

---

## 1. The idea in one paragraph

People on Earth command a robot on Mars. They can't steer it live: a radio signal takes minutes
to get there, the link is sometimes down, and the robot must protect itself. RedLink simulates
this as **two separate Spring Boot services** that talk over HTTP. **mission-control** is Earth:
users, commands, missions, the stored history. **rover-sim** is the rover: it executes commands on
a simulated terrain and reports back. Between them is a **simulated deep-space link**: signed
packets, an artificial light-time delay, retries, outages. A React app shows everything live,
including a 3D view of the site.

## 2. Real mission vs RedLink

| Real Mars mission (e.g. Perseverance) | RedLink | Where in the code |
|---|---|---|
| Mission control at JPL; operators plan the next sol's activities | `mission-control` service; users with roles VIEWER / OPERATOR / FLIGHT_DIRECTOR | `missioncontrol.user`, `security`, `auth` |
| Commands are sequences, uplinked through the Deep Space Network (DSN) | Commands and missions (up to 20 steps) are queued, numbered and sent as signed packets | `missioncontrol.command`, `mission`, `uplink` |
| One-way light time 4–24 minutes | A configurable delay, 10 s by default (`redlink.uplink.one-way-delay`) | `UplinkTransmitter` sets `arrives_at`; dispatchers wait for it |
| DSN antennas reachable only in contact windows, often through a relay orbiter | Optional relay windows (`redlink.dsn.windows`), and an orbiter drawn in the 3D sky | `dsn.LinkWindows`, `DsnService` |
| Solar conjunction: about two weeks every two years without contact | A flight director declares a conjunction; the link answers **503 + Retry-After** | `dsn.DsnController`, `LinkUnavailableException` |
| Command authentication on the uplink | HMAC-SHA256 signature over the exact body bytes, with a timestamp | `contract.link.PacketSigner`, both `LinkSignatureFilter`s |
| The rover's flight software checks every command (hazards, power) and can refuse it | `FlightRules` (pure Java), checked again before each step | `roversim.flight` |
| Safe mode: on a fault the rover stops and waits for Earth | `SAFE_MODE`: only REBOOT is accepted until then | `FlightRules`, `CommandStepper`, `FailureCode.entersSafeMode()` |
| Telemetry: engineering data, events, images | `TelemetryPacket` with COMMAND_STATUS, ROVER_STATE, EVENT, PHOTO, SAMPLE | `contract.telemetry`, `roversim.downlink` |
| Earth only knows what telemetry said | mission-control never reads the rover's database; it keeps "last known" values | `missioncontrol.rover.Rover` (`last_*` columns) |
| Navcam / Mastcam images | 320×240 PNGs rendered from the terrain (Java2D) | `roversim.camera.PhotoRenderer` |
| Drilled rock or soil samples | DRILL_SAMPLE; rock if the rover faces one, else soil; needs approval | `FlightRules.sampleMaterial`, `TelemetryPayload.Sample` |
| Risky commands are reviewed by a second person | Four-eyes rule: a drill waits for a director who didn't submit it | `Command.approve`, `FourEyesViolationException` |
| Ground planners simulate a sequence before uplink | `MissionPlanner` plays the plan forward on the same terrain and specs | `missioncontrol.mission` |
| A Mars day (sol) is 24 h 39 min; solar rovers charge only in sunlight | A sol is compressed to 12 minutes; CHARGE gains nothing at night | `contract.rover.MarsClock` |
| Dust storms reduce solar power | Random storms: charging at 25 % | `roversim.events.Weather` |
| Real elevation models (HiRISE DTMs) | Generated 128 × 128 m sites (PNG heightmap + surface map) | `contract.terrain.TerrainGenerator` |

What is **not** simulated: the real orbital mechanics, the real rover's command language, and
real latency (minutes). These are scaled down so that a demo shows everything in seconds.

## 3. The big picture

```
 Browser (React + three.js)
     │  HTTPS: REST (JSON) + SSE (live events)        user JWT (Authorization: Bearer)
     ▼
 Vite dev server :5173  ──proxy /api──►  mission-control :8080 ──► PostgreSQL "mission_control"
                                            │        ▲
                    uplink: POST /flight/uplink      │ downlink: POST /dsn/downlink
                    (signed, after the light delay)  │ (signed, after the light delay)
                                            ▼        │
                                         rover-sim :8081 ──► PostgreSQL "rover"
```

- The two services **never share tables or code**, only the `redlink-contract` jar (records and
  pure utilities, no Spring).
- Each service has its **own database** (its own user and Flyway history).
- Both directions of the link are **outboxes**: a packet is written to a table in the same
  transaction as the change it describes, and a scheduler sends it later. Section 6 explains why.

## 4. The repository

```
RedLink/
├── pom.xml                     parent POM: Java 17, Spring Boot 4.1, plugin versions, profiles
├── redlink-contract/           the shared language (no Spring)
├── redlink-mission-control/    Earth: the public REST API, port 8080
├── redlink-rover-sim/          Mars: the simulated rover, port 8081
├── frontend/                   React app (Vite, TypeScript, three.js)
├── docs/                       GUIDE, this file, SPEC, ADRs, tickets
├── scripts/local-db-setup.sql  creates the two local databases and users
└── .run/                       IntelliJ run configurations
```

Each service is **packaged by feature**: `...missioncontrol.command`, `...missioncontrol.mission`,
not `controller/service/repository`. Inside a feature you find its controller, service, entity,
repository and DTOs together.

### 4.1 `redlink-contract`

Everything both sides must agree on. Records and enums, plus a few pure utilities.

| Package | What is in it |
|---|---|
| `command` | `RoverCommand`: a sealed interface with one record per command (`Move`, `Rotate`, `TakePhoto`, `DrillSample`, `Charge`, `Reboot`), polymorphic JSON via `@JsonTypeInfo(property = "type")`. `CommandType` (also says whether a type needs approval and is allowed in safe mode). `CommandPacket`: what travels on the uplink (packet id, rover, command id, **sequence number**, command, timestamp, `traceparent`). |
| `telemetry` | `TelemetryPacket` and `TelemetryPayload`, a sealed interface with `CommandStatus`, `RoverState`, `Event`, `Photo`, `Sample`. Enums for statuses, severities and `SampleMaterial`. |
| `link` | `PacketSigner`: HMAC-SHA256 signing and constant-time verification (`X-Link-Signature: t=<epoch>,v1=<sig>`). `TraceContext`: the W3C `traceparent` format. |
| `rover` | `RoverSpecs`: the spec sheet (costs, limits, step durations), used by the rover to execute and by Earth to plan. `MarsClock`: the compressed sol, sunlight. `Heading`, `RoverMode`, `Camera`. |
| `terrain` | `Terrain` (grid of elevations and surfaces, slope, `isBlocked`), `TerrainLoader`/`TerrainWriter` (PNG ⇄ grid), `TerrainGenerator` (the two shipped sites, deterministic from a seed). Sites live in `src/main/resources/sites/<id>/`. |

Bean Validation annotations sit on the contract records, so both services validate the same way.

### 4.2 `redlink-mission-control` (Earth)

| Package | Responsibility | Key classes |
|---|---|---|
| `auth` | Login: username/password → JWT; brute-force protection | `AuthController` (`POST /api/v1/auth/token`, `GET /me`), `TokenService` (HS256 JWT, 15 min), `LoginThrottle` (failures counted in PostgreSQL, shared by all instances) |
| `security` | Spring Security for the public API | `SecurityConfig` (stateless, JWT resource server, roles, CORS, headers), Problem-Details entry point and access-denied handler |
| `user` | Users and roles | `UserAccount`, `Role`, `LocalUserSeeder` (only in the `local` profile: `viewer`, `operator`, `director`) |
| `rover` | Earth's view of each rover | `Rover` (the **last known** state from telemetry), `RoverController` (`GET`, and `PATCH` with `If-Match`) |
| `command` | Single commands: submit, list, cancel, approve, reject | `CommandController`, `CommandService`, `Command` (entity with intention-revealing methods: `cancel()`, `approve()`, `applyRoverReport()`), `CommandStatus` |
| `mission` | Multi-step plans, dry runs, route finding | `MissionService`, `MissionPlanner` (pure: plays a plan forward), `RoutePlanner` (A* to a clicked cell), `MissionAborter` (withdraws later steps when one fails) |
| `uplink` | Getting commands to Mars | `UplinkTransmitter` (sequence number + packet, one transaction), `UplinkClaims` (lease rows), `UplinkDispatcher` (sends after the delay), `RoverLinkClient` (signed HTTP POST), `UplinkScheduler` |
| `link` | Accepting packets from Mars | `LinkSignatureFilter` (verifies the HMAC before anything parses the body), `LinkSecurityConfig` (a separate filter chain for `/dsn/**`) |
| `telemetry` | Storing and applying what the rover reports | `DownlinkController` (`POST /dsn/downlink`), `TelemetryService` (dedupe by packet id, apply per type), `TelemetryController` (history) |
| `dsn` | Link availability | `DsnService`, `LinkWindows` (relay windows), `LinkState` (conjunction on/off, with ETag), `DsnController` (`GET /link`, `PUT /link/conjunction`) |
| `events` | Live updates to browsers | `RoverEventPublisher` (stores every event + `pg_notify`), `SseHub` (open streams, replay, catch-up), `RoverEventListener` (`LISTEN` for other instances), `StreamTickets` (short-lived SSE tickets), `EventsController` |
| `photo` | Images | `PhotoService`, `PhotoController` (same URL returns JSON metadata or the PNG, by `Accept`) |
| `webhook` | Notifying external systems | `WebhookService`, `WebhookDispatcher` (at-least-once, backoff, gives up after N), `WebhookSender` (signed: `X-RedLink-Signature`), `WebhookUrlPolicy` (SSRF protection) |
| `idempotency` | Safe retries of POSTs | `IdempotencyService` (`Idempotency-Key` header, stored responses, 24 h) |
| `ratelimit` | Too many submissions | `SubmissionRateLimiter` (token bucket per user → 429 + `Retry-After`) |
| `audit` | Who did what | `AuditLog` (written in the same transaction as the change), `AuditController` (`GET /audit`, directors) |
| `site`, `specs` | Read-only resources | Site metadata and its PNG maps (cacheable, ETag); the rover spec sheet |
| `web` | Cross-cutting HTTP | `ApiProblemHandler` + `ProblemTypes` (RFC 9457 errors), `EntityTags` (If-Match), `PageLinks` (RFC 8288 `Link` header on pages), strict JSON |
| `tracing` | Distributed tracing | `LinkTracing` (puts the W3C `traceparent` into packets, continues it on the other side) |
| `docs` | OpenAPI | springdoc config; polymorphic command schemas |

### 4.3 `redlink-rover-sim` (Mars)

| Package | Responsibility | Key classes |
|---|---|---|
| `uplink` | Receiving commands | `UplinkController` (`POST /flight/uplink`), `UplinkService` (exactly-once by packet id; stale or out-of-order packets), `ReceivedCommand` |
| `security` | Only Earth may talk to the rover | `LinkSignatureFilter`, `RoverSecurityConfig` (no users here at all) |
| `flight` | The flight software | `RoverExecutor` (a tick every 250 ms per rover), `CommandStepper` (one step per tick, in one transaction), `FlightRules` (**pure functions**: what a step does and whether it fails), `RoverSnapshot`, `StepOutcome`, `FailureCode`, `TerrainRegistry` |
| `rover` | The rover's **true** state | `RoverState` (position, heading, battery, storage, mode, dust storm) |
| `downlink` | Getting telemetry to Earth | `TelemetryOutbox` (write a packet in the caller's transaction), `DownlinkClaims`, `DownlinkDispatcher`, `EarthLinkClient`, `RetryAfter` (follows Earth's 503/429 hints) |
| `events` | Random things that happen on Mars | `Weather` (dust storms), `Dice` (an interface, so tests script the randomness) |
| `camera` | Photos | `PhotoRenderer` (ray-marched first-person view of the terrain, rounded boulders, shadows) |
| `web` | Errors | RFC 9457 Problem Details |

The rover has no users and no public API. It is reachable only with a valid link signature.

## 5. The life of a command, end to end

Follow one `MOVE 3` from the browser to the rover and back. Default timings in brackets.

```
 Browser        mission-control                                    rover-sim
   │ POST /api/v1/rovers/1/commands
   │ Idempotency-Key: 9f1…   {"type":"MOVE","distanceMeters":3}
   │──────────────►│ CommandController → CommandService.submit (one transaction):
   │               │   rate limit, validate, idempotency row, save Command(QUEUED),
   │               │   audit row, event row "command" (+ pg_notify)
   │◄── 202 Accepted, Location: /api/v1/commands/42, Retry-After: 5
   │               │
   │               │ [queue-hold 5 s: still cancellable → POST /commands/42/cancel]
   │               │ UplinkTransmitter (scheduler, advisory lock):
   │               │   next sequence no. for rover 1, status TRANSMITTED,
   │               │   uplink_packets row with arrives_at = now + 10 s
   │               │ [one-way delay 10 s]
   │               │ UplinkDispatcher: claim due rows (lease, SKIP LOCKED),
   │               │   RoverLinkClient: POST /flight/uplink, X-Link-Signature, traceparent
   │               │─────────────────────────────────────────────────►│ LinkSignatureFilter: verify HMAC
   │               │                                                   │ UplinkService: dedupe packet id,
   │               │                                                   │   store ReceivedCommand,
   │               │                                                   │   outbox: COMMAND_STATUS RECEIVED
   │               │◄──────────────── 202 ─────────────────────────────│
   │               │                                                   │ RoverExecutor tick (250 ms):
   │               │                                                   │  CommandStepper: next by sequence no.
   │               │                                                   │  FlightRules.step → move 1 m
   │               │                                                   │  save RoverState + outbox:
   │               │                                                   │  ROVER_STATE, COMMAND_STATUS EXECUTING
   │               │                                                   │  … 3 steps, then COMPLETED
   │               │                                                   │ [one-way delay 10 s]
   │               │◄── POST /dsn/downlink (signed) ── DownlinkDispatcher
   │               │ TelemetryService (one transaction): dedupe by packet id,
   │               │   ROVER_STATE → Rover.last_*; COMMAND_STATUS → Command status;
   │               │   event rows (+ pg_notify), webhooks queued
   │◄── SSE: event: command / event: rover ──│
```

Statuses a command goes through (`CommandStatus`):

```
PENDING_APPROVAL ──approve──► QUEUED ──transmit──► TRANSMITTED ──► RECEIVED ──► EXECUTING ──► COMPLETED
       │ reject                 │ cancel (only while on Earth)                     │
       ▼                        ▼                                                  ├──► FAILED
    REJECTED                CANCELLED                                              └──► REFUSED_BY_ROVER
```

Things worth noticing:

- **202 Accepted**, not 201: the command is accepted for processing, not done. `Location` points
  to it, and `Retry-After` hints when polling is worth it. The browser doesn't poll; it uses SSE.
- **Sequence numbers.** Packets may arrive in any order or twice. The rover runs commands strictly
  by sequence number, waits up to 30 s for a missing one (`reorder-timeout`), then skips it and
  reports `SEQUENCE_GAP_SKIPPED`. A packet arriving after its slot was skipped is refused.
- **Earth never reads the rover's state.** `Rover.last_x` etc. change only when a ROVER_STATE
  report arrives. An older report than the one already applied is stored but ignored.
- **The rover re-checks everything** (rocks, slope, battery, storage) with `FlightRules`, even
  though Earth's `MissionPlanner` already checked the plan. Earth's knowledge may be stale.
- **Four-eyes**: a DRILL_SAMPLE starts as PENDING_APPROVAL. A flight director other than the
  submitter approves it. The battery check runs again at approval time.

## 6. Reliability patterns (why the code looks the way it does)

| Problem | Pattern | Where |
|---|---|---|
| "Saved the command but crashed before sending" or "sent but the transaction rolled back" | **Transactional outbox**: the packet row is written in the same transaction as the change; a scheduler sends it afterwards | `uplink_packets` (Earth), `downlink_packets` (rover), webhook deliveries |
| Two instances (or two threads) sending the same packet | **Claim with a lease**: `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` sets `leased_until`; a crashed sender's lease expires and someone else retries | `UplinkClaims`, `DownlinkClaims`, `WebhookDeliveries` |
| Sequence numbers must be gap-free and ordered | One **PostgreSQL advisory lock** for the transmitter, and an atomic per-rover counter row | `UplinkTransmitter` |
| The network fails, the other side restarts | **At-least-once delivery** with exponential backoff (`initial-backoff` … `max-backoff`) | Dispatchers on both sides |
| At-least-once means duplicates | **Idempotent receivers**: every packet has a UUID; `INSERT … ON CONFLICT DO NOTHING` | `UplinkService`, `TelemetryService` |
| The user's browser retries a POST | **Idempotency-Key**: the first response is stored and replayed for the same key and body; the same key with a different body → 422, while the first is still running → 409 | `IdempotencyService` |
| The link is down (solar conjunction) | Earth answers the rover **503 + Retry-After: 30**; the rover's `RetryAfter` waits that long. Commands wait on Earth. Nothing is lost | `DsnService`, `RetryAfter` |
| Two users edit the same thing | **Optimistic concurrency** with `ETag` / `If-Match` → 412 Precondition Failed | `EntityTags`, rover PATCH, conjunction PUT |
| Several mission-control instances behind a load balancer | Every SSE event is stored in `rover_events`; **`pg_notify`** tells the other instances, which `LISTEN` and push it to their own browsers | `RoverEventPublisher`, `RoverEventListener`, `SseHub` |
| A browser loses its connection | SSE resumes with **`Last-Event-ID`**: missed events are replayed from the table; if too old, a `reset` event says "reload" | `SseHub`, `EventsController` |
| Retrying forever at someone else's server | Webhooks **give up** after `max-attempts` and keep a delivery log | `WebhookDispatcher` |

Spring-wise: schedulers are `@Scheduled(fixedDelay)` beans that can be switched off by property
(tests turn them off and drive the steps by hand with a `MutableClock`). Every time comes from an
injected `Clock` bean, so tests control time.

## 7. Where each HTTP / REST technique lives

| Technique | Example | Implemented in |
|---|---|---|
| Resource-oriented URLs, versioned under `/api/v1` | `/rovers/{id}/commands`, `/commands/{id}` | controllers |
| Correct status codes | 202 + `Location`, 304, 400, 401, 403, 404, 409, 412, 415, 422, 428, 429, 503 | services throw `ApiException` subclasses |
| Errors as **RFC 9457 Problem Details** with stable `type` URIs | `{"type":"https://redlink.example/problems/four-eyes-violation", "title":"Four-eyes rule", "status":403, "detail":…}` | `web.ApiProblemHandler`, `ProblemTypes` |
| **Idempotency-Key** on POST | resubmitting a command is safe | `idempotency` |
| **Conditional requests** | `ETag` + `If-None-Match` → 304; `If-Match` → 412 | `EntityTags`; for GET, Spring turns a matching `ETag` into 304 |
| **Caching** | site maps `max-age=86400`, specs `max-age=3600`, live data `no-cache` | site, specs and DSN controllers |
| **Hypermedia** | `_links` on commands and missions (`self`, `cancel`, `approve`…), only the allowed actions | `CommandResponse`, `MissionResponse` |
| **Paging with `Link` headers** (RFC 8288) | `first`, `prev`, `next`, `last` | `web.PageLinks` (a `ResponseBodyAdvice`) |
| **Content negotiation** | `GET /photos/{id}` → JSON or `image/png` by `Accept` | `PhotoController` (two mappings, `produces`) |
| **Rate limiting** | 30 submissions per minute per user → 429 + `Retry-After` | `ratelimit` |
| **Server-Sent Events** | live `rover`, `command`, `uplink`, `photo`, `sample`, `link` events | `events` |
| **Webhooks** | `command.completed`, `command.failed`, `rover.safe_mode`, `rover.battery_low`: signed, retried, SSRF-checked | `webhook` |
| **Message authentication** | HMAC over the raw body + timestamp (replay window 5 min), constant-time compare | `PacketSigner`, `LinkSignatureFilter` |
| **Authentication / authorization** | JWT bearer (HS256), roles via `@PreAuthorize` | `security`, `auth` |
| **Distributed tracing** | W3C `traceparent` carried inside the signed packets; one trace id across both services | `tracing.LinkTracing`, OpenTelemetry starter |
| **API documentation** | OpenAPI 3 at `/v3/api-docs`, Swagger UI at `/swagger-ui.html` | springdoc, `docs` |

## 8. Security

- **Two separate filter chains** in mission-control:
  - `/api/**`: users. Stateless JWT (`Authorization: Bearer`), roles VIEWER < OPERATOR <
    FLIGHT_DIRECTOR, method security (`@PreAuthorize("hasRole('OPERATOR')")`).
  - `/dsn/**`: the link. No user at all, only a valid HMAC signature (`LinkSecurityConfig`).
- The rover-sim has only the link chain.
- **Login throttle**: 5 failures in 15 minutes per user and client → 429; counted in PostgreSQL so
  all instances agree (`LoginThrottle`).
- **SSE tickets**: `EventSource` can't send headers, and a JWT in a URL ends up in logs. The browser
  exchanges its JWT for a 30-second, single-use ticket and puts that in the URL.
- **SSRF**: webhook URLs are resolved, and every address must be public (no 127.0.0.1, private
  ranges or 169.254.169.254), checked again before every delivery.
- **No secrets in the repository.** Passwords and keys come from environment variables; local dev
  defaults exist only in `application-local.yml`.
- Only the `health` and `info` actuator endpoints are exposed. Errors never contain stack traces
  or SQL.

## 9. The databases

All schema changes go through Flyway (`src/main/resources/db/migration`). Hibernate only
validates (`ddl-auto: validate`), and `open-in-view` is off.

**mission_control** (V1–V17): `users`, `rovers` (last known state), `commands`,
`idempotency_records`, `uplink_sequences`, `uplink_packets`, `telemetry` (every report, JSONB),
`rover_events` (the SSE log), `webhooks` + deliveries, `photos` (PNG `bytea`), `missions`,
`link_state` (conjunction), `audit_log`, `login_failures`.

**rover** (V1–V7): `rover_state` (the truth), `received_commands`, `downlink_packets`.

Some choices worth knowing:

- Plain JDBC (`JdbcClient`) where SQL does the work better: claiming rows with
  `FOR UPDATE SKIP LOCKED`, upserts, `pg_notify`, advisory locks. JPA for normal entities.
- `Instant`s are stored as `timestamptz` in UTC and truncated to microseconds, so a value
  returned now equals the value read back later.
- Entities use Lombok `@Getter` + a protected no-args constructor, and change only through
  methods that say what happens (`command.cancel(now)`), never through setters.

## 10. Tests

| Kind | Naming | Runs with | Example |
|---|---|---|---|
| Pure unit tests | `*Test` | surefire, `./mvnw test` | `FlightRulesTest`, `MissionPlannerTest`, `MarsClockTest` |
| Web slices (MockMvc) | `*Test` | surefire | the security slice: exact 401/403/429 answers |
| Integration tests against **real PostgreSQL** | `*IT` | failsafe, `./mvnw verify` | `CommandsIT`, `UplinkIT`, `EventsIT`, `CommandExecutionIT` |

- ITs use Testcontainers normally. On this machine Docker hangs, so run
  `./mvnw verify -Plocal-db`: it uses the local PostgreSQL with a separate `it` schema.
- ITs switch the schedulers off and drive time with `MutableClock`, so a 10-second light delay
  takes no real time.
- Every HTTP feature has a test for the exact status code and headers.

## 11. Running it

1. PostgreSQL 17 with the two databases (`scripts/local-db-setup.sql`).
2. In IntelliJ (the `.run/` configurations), with the `local` profile:
   - mission-control (8080), then rover-sim (8081);
   - Flyway migrates both databases on start.
3. `npm run dev` in `frontend/` → http://localhost:5173. Log in as `viewer`, `operator` or `director`,
   password `redlink-dev`.

The important settings (all in `application.yml`, overridable by environment variables):

| Setting | Default | Meaning |
|---|---|---|
| `redlink.uplink.one-way-delay` | 10 s | simulated light time Earth → Mars |
| `redlink.uplink.queue-hold` | 5 s | how long a new command stays cancellable on Earth |
| `redlink.rover.tick` | 250 ms | how often the flight software runs |
| `redlink.rover.charge-minute` | 1 s | one simulated charging minute |
| `redlink.rover.events.*` | storm 5 %/min | random events; `enabled: false` turns them off |
| `redlink.dsn.windows.enabled` | false | relay windows every 2 min for 45 s |
| `redlink.rate-limit.submissions` | 30/min | per user |

## 12. The frontend (overview only)

A React 19 single-page app in TypeScript, built with Vite.

| Folder | What it does |
|---|---|
| `src/api/` | The only place that talks to the backend: `client.ts` (fetch + JWT + Problem Details), `events.ts` (SSE with tickets and resume), `schema.d.ts` (TypeScript types **generated from the OpenAPI document**: `npm run gen:api`) |
| `src/components/` | The screens and panels: login, rover picker, command console, command timeline, missions, photos, telemetry charts, link panel, approvals, audit and webhook dialogs |
| `src/state/` | The page state as a reducer, fed by REST loads and SSE events |
| `src/scene/` | The 3D view (three.js via react-three-fiber): the site built from the same PNG maps the rover drives on, the scenery around it, the rover model, signal beams, photo pins, drill marks, the sol cycle and the intro film |
| `src/terrain/` | Decoding the site PNGs, the 2D map colours |
| `src/console/` | Battery/time estimates before sending, using `GET /specs` |

How it uses the backend:

1. Login → JWT in memory.
2. REST to load the current state (rover, commands, photos, link…).
3. One SSE stream per rover keeps everything live; nothing polls.
4. All numbers (positions, battery) come from the API. The 3D view animates between reported
   positions; it never invents state.

## 13. Suggested reading order in the code

1. `redlink-contract`: `RoverCommand`, `CommandPacket`, `TelemetryPayload`, `PacketSigner`,
   `RoverSpecs`.
2. mission-control `command`: `CommandController` → `CommandService.submit` → `Command`.
3. `uplink`: `UplinkTransmitter` → `UplinkClaims` → `UplinkDispatcher` → `RoverLinkClient`.
4. rover-sim: `UplinkController` → `UplinkService` → `RoverExecutor` → `CommandStepper` →
   `FlightRules` → `TelemetryOutbox` → `DownlinkDispatcher`.
5. Back on Earth: `DownlinkController` → `TelemetryService` → `RoverEventPublisher` → `SseHub`.
6. Then the side topics: `mission` (planner, A*), `dsn`, `webhook`, `idempotency`, `web`
   (errors, ETags, paging), `security`.
7. Read a matching integration test next to each, for example `CommandsIT`, `UplinkIT`,
   `CommandExecutionIT`. The tests show the exact HTTP exchanges.
