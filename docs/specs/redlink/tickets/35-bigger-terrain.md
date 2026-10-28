# 35 — Bigger, more beautiful terrain

Status: ready-for-human

## Scope

The 3D view only (frontend). The navigable site stays 64 × 64 cells: the rover simulation, the
planner and the API do not change.

- **A landscape around the site:**
  - procedural ground out to about 700 m that joins the site's edges without a seam;
  - rolling hills, a few impact craters, the crater rim as a mountain wall on the horizon;
  - a layered peak to the south-east (like Gale's Mount Sharp) as a landmark.
  - It replaces the flat ring and the cylinder wall (`CraterRim`).
- **A finer site mesh:**
  - 3 vertices per cell, following the same bilinear surface as `heightAt`;
  - small dips (never bumps, so tracks and markers stay above the ground);
  - colour varies at two scales.
- **Ground detail:** a tiling noise texture used as colour and bump map, for grain up close.
- **Rocks:** lumpy boulders instead of 12-sided dice, and pebbles scattered on the site and
  around it (decoration only, not obstacles).
- **Camera and atmosphere:**
  - fog pushed out so the horizon shows, and the far plane raised;
  - stars moved behind the mountains;
  - orbit zoom-out raised to 160 m.

## Acceptance criteria

- [x] The landscape height at the site's edge equals the site's surface (minus a small skirt
  hidden under it); no gap or cliff at the boundary.
- [x] The fine site mesh never rises above the bilinear surface the rover and markers use.
- [x] Pure functions tested (noise range and determinism, edge join, fine mesh sizes).
- [x] The camera modes still work. Clicking a cell and the minimap use the unchanged `worldToCell`; not clicked through in the browser this time.
- [x] Checked in the browser at 1600 × 1000; the frame rate stays smooth (about 70 fps on the GPU).
- [x] Type check, Vitest and build green.

## Checklist

- [x] `scene/relief.ts` (noise, landscape height and colour, fine site arrays) + tests
- [x] `scene/Landscape.tsx` (landscape mesh, pebbles, rock geometry, ground texture)
- [x] `Terrain` uses the fine mesh and the texture; `Rocks` the lumpy geometry; `CraterRim` removed
- [x] Sky fog, stars, camera far plane, orbit max distance
- [x] Screenshots
- [x] GUIDE §7 and the ticket index

## Verification

- `relief.test.ts` (6 tests):
  - noise range, determinism and tiling;
  - the landscape joins the site's edge a skirt below it, and rises to the rim;
  - the fine mesh never rises above the rover's surface;
  - the landscape squares under the site are left out;
  - the grain texture's range.
- Vitest 61 passed, type check clean, build OK.
- Browser (headless Edge on the GPU, 1600 × 1000):
  - default, zoomed-out, top-down, follow, low horizon (both directions) and night views;
  - about 70 fps.
- Note: in software WebGL (SwiftShader) the whole frame came out empty from some camera angles.
  On the GPU every angle renders, so it is a limit of the test renderer, not of the scene.
