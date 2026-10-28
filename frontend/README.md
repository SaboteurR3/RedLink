# RedLink frontend

The mission-control screen: React 19 + TypeScript + Vite, with a 3D view of the landing site
(React Three Fiber) and a 2D map as the alternative and the fallback when WebGL is unavailable.

- `src/scene/geometry.ts`: the scene maths (grid to world, heights, terrain arrays, beam timing,
  headings), unit tested.
- `src/scene/*.tsx`: the 3D parts. `Scene3D` is lazy-loaded, so three.js (about 250 kB gzipped) is
  only downloaded by browsers that open the 3D view.

## Run

Start mission-control and rover-sim first (see the root README), then:

```sh
npm install        # once
npm run dev        # http://localhost:5173
```

Log in as `operator` / `redlink-dev` (or `viewer`, `director`). The dev server forwards `/api` to
mission-control on port 8080, so the browser only ever talks to one origin.

## Scripts

| Script | What |
|---|---|
| `npm run dev` | dev server with hot reload |
| `npm run build` | type-check (`tsc`) + production bundle in `dist/` |
| `npm test` | unit tests (Vitest) |
| `npm run gen:api` | regenerate `src/api/schema.d.ts` from the running backend's OpenAPI document |

Run `npm run gen:api` after changing the backend API, then `npm run build`: a contract change
that breaks the frontend fails the type-check instead of failing at runtime.

## How it uses the API

- `src/api/client.ts`: bearer token, RFC 9457 problems as readable errors, and command
  submission with an `Idempotency-Key` that is **reused on retry** (a flaky connection can never
  send a command twice).
- `src/api/events.ts`: the live stream. A single-use stream ticket per connection (EventSource
  cannot send headers); on any error a new ticket and resume with `lastEventId`.
- `src/state/mission.ts`: the screen's state, updated only from API answers and stream events. The
  rover moves on the map when telemetry says so, never when a button is clicked.
- The Cancel button appears only when the server puts a `cancel` link into the command (HATEOAS).

## Notes

- `.npmrc` sets `legacy-peer-deps`: npm 10 crashes resolving the optional peers of Vitest and
  the React plugin. All required peers are declared in `package.json`.
- Dependency versions are exact and were at least two weeks old when pinned.
- After `npm install` adds a dependency, restart a running `npm run dev`: Vite's pre-bundled
  dependency cache is stale until then (the browser gets 504 "Outdated Optimize Dep").
