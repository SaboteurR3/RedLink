# 19 — Link windows, solar conjunction, random events

Status: ready-for-human

## Scope

The deep-space link is not always available, and Mars does not always cooperate.

### Link windows (mission-control)

`redlink.dsn.windows {enabled, every, duration}` models relay-orbiter passes: the uplink can only
transmit while a window is open.

- Windows repeat from a fixed epoch (Unix 0), so every instance agrees on the schedule without
  coordination.
- Outside a window, commands simply stay QUEUED; the hold, cancel and approve rules still apply.
- Off by default in `local` and `test`.

### Solar conjunction (mission-control)

The Sun is between Earth and Mars, so nothing gets through in either direction.

- `link_state` table, a single row with a version.
- `GET /api/v1/link/conjunction` (VIEWER): `{active, changedBy, changedAt}` with ETag, 304.
- `PUT /api/v1/link/conjunction` (FLIGHT_DIRECTOR), body `{active}`:
  - idempotent: the same value changes nothing and is still 200;
  - optional `If-Match` → 412 when stale;
  - answers 200 with the new ETag;
  - Bean Validation: `active` is required.
- While active, the uplink holds every command (they stay QUEUED and wait).
- While active, `/dsn/downlink` answers `503 link-unavailable` with `Retry-After`. The rover keeps
  its telemetry (store-and-forward) and sends it again later.
- A change is pushed to every rover's event stream as SSE `link`.

### Link status (mission-control)

`GET /api/v1/link` (VIEWER) gives one view of the link:

- `oneWayDelaySeconds`;
- `window {enabled, open, nextChangeAt}`;
- `conjunction {…}`;
- `uplinkOpen`.

It is time-dependent, so `Cache-Control: no-cache` and no ETag.

### Retry-After on the rover

`EarthLinkClient` reads `Retry-After` (delta-seconds or HTTP-date, capped at 1 h) on 503 and 429.
The dispatcher waits `max(backoff, Retry-After)`, so the rover honours the server's hint instead
of hammering it.

### Random events (rover-sim)

Configured with `redlink.rover.events {enabled, weather-interval, dust-storm-chance-per-minute,
dust-storm-duration, wheel-stuck-chance-per-meter}`. All randomness goes through a `Dice` bean,
so tests are deterministic.

- **Dust storm:** a weather tick may start one (`rover_state.dust_storm_until`, migration V4),
  with EVENT `DUST_STORM_STARTED` (WARNING). When it is over: `DUST_STORM_ENDED` (INFO). During a
  storm, charging gains `RoverSpecs.DUST_STORM_CHARGE_FACTOR` (¼) of the nominal rate.
- **Stuck wheel:** each meter of a MOVE may stop with FAILED `WHEEL_STUCK`. It is not a hazard,
  so no safe mode; the drive can be retried.
- `FlightRules.step` takes `Conditions(dustStorm, wheelStuck)`; the existing 3-argument form means
  nominal conditions. Earth's `MissionPlanner` still plans nominally; its forecast is "nominal",
  which the API description says.

### Frontend

- The link panel shows the window (open/closed, countdown) and a conjunction banner, plus a
  FLIGHT_DIRECTOR toggle using `If-Match` from the conjunction ETag. It refreshes on SSE `link`.
- The 3D scene thickens the dust and fog while the latest rover event is `DUST_STORM_STARTED`.

## Acceptance criteria

- [x] Window closed → nothing transmitted; open → transmitted (unit test of the schedule + IT).
- [x] Conjunction: PUT 200 + ETag, again 200 without a version bump; 412 for a stale If-Match;
      403 for OPERATOR; 400 without `active`; GET 304.
- [x] Conjunction active: uplink holds; downlink 503 + `Retry-After` + problem; telemetry not stored.
- [x] GET /link shows window and conjunction.
- [x] Rover honours Retry-After (client test against a stub; dispatcher schedules accordingly).
- [x] Dust storm starts/ends with events; charging is slower during it; stuck wheel fails a MOVE
      without safe mode (deterministic dice).
- [x] Frontend link panel + dust effect; `npm test` / build pass.
- [x] `./mvnw verify -Plocal-db` green.

## Checklist

- [x] mission-control: `dsn` package (windows, link state, service, controller), V11 migration, problem type, SSE `link`
- [x] Uplink gate; downlink 503
- [x] rover-sim: Retry-After in client + dispatcher
- [x] rover-sim: `Dice`, events properties, weather, V4 migration, conditions in FlightRules, WHEEL_STUCK
- [x] RoverSpecs: dust storm factor
- [x] Tests (unit + IT) for all of the above
- [x] Frontend
- [x] SPEC, README index
- [x] Full build green

## Notes for later tickets

- 20 (compose): `redlink.dsn.windows` and `redlink.rover.events` can be set by environment
  variables for a demo (e.g. a higher dust-storm chance).
- 21 (write-up): ADR `link-availability-503-retry-after`; the live conjunction run below is a good
  demo script step.
- Not built: the real current Earth–Mars light time in the UI.

## Verification

- `./mvnw verify -Plocal-db`: 203 unit tests (contract 75, mission-control 91, rover-sim 37) and
  163 integration tests (mission-control 137, rover-sim 26), all green. Frontend: 33 tests, build OK.
- New tests: `LinkWindowsTest`, `LinkIT` (windows, conjunction PUT/GET/412/403/400/401, uplink hold,
  downlink 503), `RetryAfterTest`, `DownlinkIT` (Retry-After honoured, rest of batch waits),
  `FlightRulesTest` (storm charging, stuck wheel), `RandomEventsIT` (storm start/end events, slower
  charging, stuck wheel without safe mode), `dustStormActive` in the frontend.
- Live run with both apps:
  - command A left Earth, then the director declared a conjunction (200, ETag "1");
  - command B, sent during the conjunction, stayed QUEUED, and `/link` showed `uplinkOpen: false`;
  - A's telemetry got 503 on Mars and the rover rescheduled it about 30 s later (Retry-After, not
    the 1 s backoff);
  - after the director ended the conjunction, both A and B completed.
- Not seen live: a dust storm and a stuck wheel (random; covered by `RandomEventsIT` with scripted
  dice), and the frontend link panel and storm effect (built and type-checked, not opened in a browser).
