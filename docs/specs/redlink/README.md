# RedLink — spec index

Source spec: [SPEC.md](SPEC.md). Work is delivered one ticket at a time; each ticket is
independently buildable and testable. Status values: `todo`, `in-progress`, `ready-for-human`, `done`.

| # | Ticket | Status |
|---|---|---|
| 01 | [Monorepo skeleton](tickets/01-monorepo-skeleton.md) | ready-for-human |
| 02 | [Contract: command & telemetry dictionaries, packet signing](tickets/02-contract-dictionaries.md) | ready-for-human |
| 03 | [Terrain: generated heightmap + loader](tickets/03-terrain.md) | ready-for-human |
| 04 | [Security baseline (JWT login, roles, link authentication)](tickets/04-security-baseline.md) | ready-for-human |
| 05 | [rover-sim: uplink endpoint + dedupe](tickets/05-rover-uplink.md) | ready-for-human |
| 06 | [rover-sim: executor, rover state, fault protection](tickets/06-rover-executor.md) | ready-for-human |
| 07 | [rover-sim: downlink outbox + dispatcher](tickets/07-rover-downlink.md) | ready-for-human |
| 08 | [mission-control: rovers API, ETag/If-Match, Problem Details](tickets/08-rovers-api.md) | ready-for-human |
| 09 | [mission-control: commands API (202, Idempotency-Key, HATEOAS)](tickets/09-commands-api.md) | ready-for-human |
| 10 | [mission-control: uplink outbox + dispatcher](tickets/10-uplink-dispatcher.md) | ready-for-human |
| 11 | [mission-control: downlink receiver, lifecycle from telemetry](tickets/11-downlink-lifecycle.md) | ready-for-human |
| 12 | [SSE event stream](tickets/12-sse-events.md) | ready-for-human |
| 13 | [Frontend v1: login, 2D map, console, timeline](tickets/13-frontend-v1.md) | ready-for-human |
| 14 | [Approval workflow (FLIGHT_DIRECTOR)](tickets/14-approvals.md) | ready-for-human |
| 15 | [Webhooks (HMAC, outbox, backoff, SSRF protection)](tickets/15-webhooks.md) | ready-for-human |
| 16 | [Photos (Java2D, content negotiation)](tickets/16-photos.md) | ready-for-human |
| 17 | [Missions (batch plans)](tickets/17-missions.md) | ready-for-human |
| 18 | [Frontend 3D scene](tickets/18-frontend-3d.md) | ready-for-human |
| 19 | [Link windows, solar conjunction, random events](tickets/19-link-windows-events.md) | ready-for-human |
| 20 | [Full-stack Docker Compose + end-to-end test](tickets/20-full-stack-compose-e2e.md) | ready-for-human |
| 21 | [Write-up and demo script](tickets/21-write-up.md) | ready-for-human |
| 22 | [QA pass (end to end) + product guide](tickets/22-qa-pass.md) | ready-for-human |
| 23 | [CI on GitHub Actions](tickets/23-ci.md) | ready-for-human |
| 24 | [UX quick wins: blocking reasons, director inbox, toasts, smarter console](tickets/24-ux-quick-wins.md) | ready-for-human |
| 25 | [Path preview (dry run) shown on the terrain](tickets/25-path-preview.md) | ready-for-human |
| 26 | [Click-to-drive with route planning](tickets/26-click-to-drive.md) | ready-for-human |
| 27 | [Missions panel, photo viewer, webhooks page, first-visit hints](tickets/27-panels.md) | ready-for-human |
| 28 | [Telemetry charts](tickets/28-charts.md) | ready-for-human |
| 29 | [3D wow I: sol cycle, sky, tracks, rover animations, post-processing](tickets/29-3d-wow-1.md) | ready-for-human |
| 30 | [3D wow II: intro flight, relay orbiter, dust storm, minimap, photo cones, alerts](tickets/30-3d-wow-2.md) | ready-for-human |
| 31 | [REST extras: Link paging headers, rate limiting, audit log](tickets/31-rest-extras.md) | ready-for-human |
| 32 | [Multiple rovers](tickets/32-multi-rover.md) | ready-for-human |
| 33 | [Distributed tracing across the link](tickets/33-tracing.md) | ready-for-human |
| 34 | [Multi-instance live updates (LISTEN/NOTIFY)](tickets/34-multi-instance-sse.md) | ready-for-human |
| 35 | [Bigger, more beautiful terrain](tickets/35-bigger-terrain.md) | ready-for-human |
| 36 | [Shining night sky and a more beautiful intro](tickets/36-night-sky-and-intro.md) | ready-for-human |
| 37 | [Drill samples: rock or soil, with a mark on the map](tickets/37-drill-samples.md) | ready-for-human |
| 38 | [Solar charging follows day and night](tickets/38-solar-day-night.md) | ready-for-human |
| 39 | [Bigger drivable sites (128 × 128 m)](tickets/39-bigger-sites.md) | ready-for-human |
| 40 | [Rocks in rover photos look like rocks, not cubes](tickets/40-realistic-photo-rocks.md) | ready-for-human |

Tickets 02–21 are outlines. Each one is detailed (scope, acceptance criteria, testing) when it
is picked up, so it reflects what the earlier tickets actually produced.

## Ticket outlines

- **02 Contract** — `CommandType` (param type, duration, `requiresApproval`), sealed `CommandParams`
  records with Jakarta Validation, `CommandPacket`, `TelemetryPacket`, `RoverMode`, `Heading`,
  `PacketSigner` (HMAC-SHA256 over timestamp + body, constant-time verify, replay window).
- **03 Terrain** — seeded heightmap + obstacle mask + `site.json`; immutable `Terrain` and
  `TerrainLoader` in the contract.
- **04 Security** — `users` table, bcrypt, `POST /api/v1/auth/token` issuing short-lived JWTs
  (issuer/audience checked), roles VIEWER / OPERATOR / FLIGHT_DIRECTOR, method security,
  stateless, CORS limited to the frontend origin, 401/403 as Problem Details, login rate limit,
  dev users only under `local`; `/flight/**` and `/dsn/**` accept only HMAC-signed link packets.
- **05 Rover uplink** — `POST /flight/uplink`, dictionary validation, dedupe on packet id.
- **06 Rover executor** — `rover_state` with `@Version`, step-wise execution, terrain checks,
  SAFE_MODE fault protection.
- **07 Rover downlink** — outbox, `FOR UPDATE SKIP LOCKED`, signed delivery, backoff.
- **08 Rovers API** — ETag/304, If-Match/412/428, global Problem Details advice, springdoc.
- **09 Commands API** — 202 + Location + Retry-After, required `Idempotency-Key`, replay/422,
  HATEOAS links.
- **10 Uplink dispatcher** — outbox written in the same transaction, signed dispatch.
- **11 Downlink + lifecycle** — signed receiver, dedupe, guarded state machine, cancel 409.
- **12 SSE** — after-commit events, heartbeat, `Last-Event-ID`, short-lived stream ticket.
- **13 Frontend v1** — Vite + React + TS in `frontend/`, generated client, 2D map, console, timeline.
- **14 Approvals** — FLIGHT_DIRECTOR approves, never the creator (four-eyes rule).
- **15 Webhooks** — secret shown once, signed deliveries, backoff, SSRF protection.
- **16 Photos** — Java2D render, `Accept`-based JSON/PNG, 406 otherwise.
- **17 Missions** — whole-plan validation, atomic queueing.
- **18 3D** — R3F terrain, rover model, signal beam, HUD, intro flight.
- **19 Link windows** — relay passes, solar conjunction, dust storms.
- **20 Compose + E2E** — Dockerfiles (non-root, layered), full compose, end-to-end test.
- **21 Write-up** — chapters from SPEC §14 and the demo script.
