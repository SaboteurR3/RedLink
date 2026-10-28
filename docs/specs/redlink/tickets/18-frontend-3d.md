# 18 — Frontend 3D scene

Status: ready-for-human

## Scope

Replace the 2D map with a 3D view of the landing site built with React Three Fiber. The 2D map
stays available as a toggle and as the fallback when WebGL is unavailable. The scene moves only
on data from the API and SSE; the backend stays the source of truth.

- **Libraries** (exact versions, all at least two weeks old): `three@0.186.1`,
  `@react-three/fiber@9.8.1`, `@react-three/drei@10.7.9`, `@types/three@0.186.0`. The scene is
  lazy-loaded (`React.lazy`), so the login page and the 2D view don't pay for three.js.
- **Terrain:** a mesh from the same `heightmap.png` and `surface.png` the rover simulates on, one
  vertex per cell centre, true scale in meters with elevation exaggerated ×1.5 for readability.
  Vertex colours come from the existing `colorize()`. Rocks are drawn as boulders on their cells.
  - Grid → world: `x` (column) → +X, `y` (row, 0 = north) → +Z, so north is −Z.
- **Rover:** a procedural model (body, mast with camera head, six wheels, solar deck); no
  external glTF, so nothing is downloaded and there is no licence question. It sits on the
  terrain at its last known cell and heading. When a new state arrives it glides there and
  turns the shortest way. Safe mode shows a pulsing red beacon.
- **Signal beam:** each uplink still in flight (SSE `uplink`) is an arc from the sky to the rover
  with a pulse. The pulse position is computed from the real `transmittedAt` and `arrivesAt`, so
  it reaches the rover exactly when the packet does. Once arrived, the beam fades.
- **Photo pins:** a small marker where each photo was taken, pointing the way the camera looked.
- **Mission forecast:** a translucent "ghost" rover where the last mission planned in this
  browser is forecast to end (it shows until the mission ends or the page reloads).
- **Atmosphere:** butterscotch sky colour, fog for distance haze, low sun with shadows, drifting
  dust (drei `Sparkles`).
- **Camera modes:** orbit (drag/zoom around the rover), follow (behind the rover, smoothly),
  top-down. On load the camera eases in from high above the crater (a short "landing" flight).
- **HUD:** the existing side panels (telemetry, link, photos) stay next to the scene, plus a
  small overlay with mode and camera buttons.
- **Pure, tested logic** in `src/scene/`:
  - grid↔world mapping;
  - height sampling;
  - geometry arrays from the maps;
  - beam progress from times;
  - shortest-turn heading interpolation.

  The WebGL rendering itself is not unit tested (no WebGL in Vitest).

Not in scope (noted for later): an intro flight Earth → Mars, the NASA rover model,
post-processing, sol day/night (ticket 19's events would drive it).

## Acceptance criteria

- [x] 3D view shows the terrain from the site files with the rover at its last known cell and heading.
- [x] Rover glides on a new `rover` event; safe mode shows a beacon (built, not yet seen in a browser: the rover stayed NOMINAL during the check).
- [x] Uplink beam pulse reaches the rover at `arrivesAt`.
- [x] Photo pins and the mission ghost appear.
- [x] Camera modes work; 2D/3D toggle; 2D fallback without WebGL.
- [x] three.js is in a separate lazy chunk.
- [x] Unit tests for the scene maths; `npm test` and `npm run build` pass.
- [x] Checked in a real browser against the running apps.

## Checklist

- [x] Dependencies installed (exact versions)
- [x] `src/scene/` maths + tests
- [x] Terrain mesh, rocks
- [x] Rover model + motion + safe-mode beacon
- [x] Signal beams
- [x] Photo pins, mission ghost
- [x] Atmosphere, camera modes, landing ease-in
- [x] Toggle + WebGL fallback + lazy loading
- [x] Browser check
- [x] SPEC, README index

## Notes for later tickets

- 19 (link windows, events): a solar conjunction can dim or hide the beams; dust storms can raise
  the `Sparkles` count and fog density; sol day/night can drive the sun light.
- 20 (compose): the production build serves `Scene3D` as its own chunk; the nginx (or Spring)
  static config must serve all of `dist/assets`.
- The developer's Vite on 5173 started before three.js was installed and keeps answering 504
  "Outdated Optimize Dep" until it is restarted.

## Verification

- `npm test`: 32 tests (7 new scene-maths tests). `npm run build`: main chunk 77 kB gzipped,
  `Scene3D` chunk 256 kB gzipped (lazy). Typecheck clean.
- Backend unchanged in this ticket; the last `./mvnw verify -Plocal-db` (ticket 17) is green.
- Browser check with headless Edge (SwiftShader WebGL) against both apps and a fresh Vite:
  - logged in and saw the landing flight;
  - planned a 3-step mission (rotate west, photo, move 3): the ghost appeared at the forecast
    (31, 29), three beams flew with countdowns in the link panel, and the rover turned and glided
    to (31, 29);
  - follow, top-down and 2D views all work;
  - after the fixes there are no console errors and no horizontal overflow.
- Fixed during the check: photo pins were too large, the side column overflowed with many photo
  thumbnails, and a 404 for `/favicon.ico` (now an inline SVG icon).
