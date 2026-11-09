# 40 — Rocks in rover photos look like rocks, not cubes

Status: ready-for-human

## Problem (from the user)

The 3D view draws lumpy boulders, but the photos the rover takes showed rocks as boxes.
`PhotoRenderer` (a voxel-space renderer that casts one ray per image column) raised a rock cell's
whole 1 × 1 m square by a flat 0.7 m and gave each cell one flat colour.

## Scope

All changes are in `PhotoRenderer`; it stays deterministic, Java2D only, without fonts.

- **Boulders:** a lumpy dome per rock cell:
  - an irregular outline (three harmonics), a flattish top, knobs and dents from smooth noise;
  - 0.3–0.7 m high, lower than they are wide;
  - sized by the same per-cell hash as the 3D view's boulders;
  - a boulder may spill a little into neighbouring cells.
- **Light:** from the surface normal and a fixed, low western sun, so boulders have a lit side and
  a shaded side, and they **cast shadows** on the ground.
- **Ground:** ground and sand blend between cell centres (no hard squares), with fine smooth grain
  instead of per-cell blocks; rocks are mottled and a little dusty on top.
- **Beyond the map:** the ground rolls on from the edge and rises slowly towards the crater rim,
  instead of a flat wall.
- Finer ray steps near the camera remove vertical banding on close rocks; a photo still takes
  about 0.1 s.

## Acceptance criteria

- [x] A boulder is a rounded dome (highest near its middle, lower towards the edge, nothing a cell
  away) with an irregular outline; different rocks have different shapes. Unit tests.
- [x] The same view gives the same PNG bytes; the existing photo tests and the rover's photo IT pass.
- [x] Photos checked by eye (close MASTCAM facing a rock, wide NAVCAM, start views).
- [x] rover-sim `verify` green.

## Verification

- `PhotoRendererTest`: three new tests (dome, irregular outline, different rocks); its views moved
  to the new start cell (64, 64).
- rover-sim: 48 unit and 32 integration tests green. mission-control does not use the renderer.
- Rendered with a throwaway program against the real `jezero-sim` site (scratchpad, not
  committed): before, rocks were boxes and the map edge was a wall; after, low lumpy boulders with
  shadows and a rolling horizon.
