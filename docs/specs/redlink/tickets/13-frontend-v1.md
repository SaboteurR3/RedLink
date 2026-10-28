# 13 — Frontend v1

Status: ready-for-human
Source spec: ../SPEC.md §10

## Scope

A working 2D mission-control screen in `frontend/` that uses every web-service feature of the
backend. The 3D scene replaces the map in ticket 18; everything else stays.

### Stack
- React 19 + TypeScript 5.9 + Vite 8, plain CSS (dark "mission control" theme); Vitest for logic.
- Exact dependency versions, each released at least two weeks before pinning.
- Types for every request/response **generated from the OpenAPI document**
  (`npm run gen:api` → `src/api/schema.d.ts`, committed), so frontend and backend cannot drift apart silently.
- Dev server proxies `/api` to mission-control: same origin in development, no CORS needed
  (CORS stays configured for deployments with separate origins).

### Features
- **Login** (`/auth/token`), token kept in memory + `sessionStorage` (survives a reload, gone
  with the tab); a 401 sends the user back to login. Role from `/auth/me`: viewers see no console.
- **API client**: bearer header, RFC 9457 problems shown as readable messages (title + detail +
  validation errors), `Idempotency-Key` generated once per submit and **reused on retry** after a
  network error, so a retried click can never create a second command.
- **Live stream**: stream ticket → `EventSource`; on error a new ticket and resume with
  `lastEventId`; `reset` reloads state.
- **Terrain map**: heightmap + surface PNGs (fetched with the token, drawn on a canvas), rocks,
  sand, rover with heading; the rover moves only when telemetry says so ("Earth only knows the past").
- **Command console**: forms for every command in the dictionary, with the dictionary's limits.
- **Command timeline**: live status and progress; **Cancel** button shown only when the command's
  `_links.cancel` exists (HATEOAS: the server decides what is allowed).
- **Telemetry panel**: battery, temperature, storage, mode, "reported N s ago (Mars time)".
- **Link panel**: signals in flight to Mars with a countdown to `arrivesAt`; rover events log.

## Out of scope

3D (18), approvals UI (14), photos (16), missions (17), production build serving (20).

## Acceptance criteria

- `npm run build` (type-check + bundle) and `npm test` pass.
- With both apps running: log in, see the map and the rover, send MOVE, watch QUEUED →
  TRANSMITTED (signal countdown) → EXECUTING → COMPLETED and the rover move; cancel a queued
  command; a viewer sees no console.

## Checklist

- [x] Ticket detailed
- [x] Project scaffold (package.json, tsconfig, Vite config with proxy, index.html)
- [x] Generated API types
- [x] API client (auth, problems, idempotent submit) + tests
- [x] Event stream client (tickets, resume, reset) + tests
- [x] Mission state reducer + tests
- [x] Components: login, map, console, timeline, telemetry, link panel
- [x] `npm run build` (tsc + bundle, 75 KB gzip) and `npm test` (22 tests) green; backend `ApiDocsIT` asserts the discriminator fix
- [x] Live check through the Vite proxy with both apps: index, login, heightmap PNG, SSE unbuffered (QUEUED → TRANSMITTED + uplink → RECEIVED → EXECUTING → COMPLETED). Visual check in a browser: by the user

## Notes for later tickets

- Found by generating the types: the OpenAPI document gave polymorphic subtypes the discriminator
  value of their class name ("Move") while the API uses "MOVE"; generated clients would have sent
  the wrong type. Fixed in mission-control (`PolymorphicSchemas`, mapping built from
  `@JsonSubTypes`), regression-tested in `ApiDocsIT`.
- Backend follow-up: springdoc marks no record component as required, so every generated
  property is optional; `src/api/types.ts` narrows them. A schema customizer could mark record
  components required (and nullable ones as such) so the generated types are exact.
- Ticket 14: approve/reject buttons follow `_links.approve` / `_links.reject` exactly like Cancel.
- Ticket 18: `TerrainMap` is the piece to replace; state, stream and console stay.
- Ticket 20: serve `frontend/dist` from nginx with `/api` proxied to mission-control (same
  origin, as in development); disable proxy buffering for the events path.
