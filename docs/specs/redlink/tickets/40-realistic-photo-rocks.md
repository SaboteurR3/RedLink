# 40 — Rocks in rover photos look like rocks, not cubes

Status: todo

## Problem (from the user)

The 3D view draws lumpy boulders, but the photos the rover takes (`PhotoRenderer`, a voxel-space
renderer drawing column by column from the terrain) show rocks as flat-topped blocks: each rock
cell becomes a box.

## Idea

- Render each rock as a rounded, irregular boulder in the photo:
  - a deterministic shape per cell (the same hash as the 3D view's rock size), drawn as a shaded
    ellipsoid or noisy blob, with its own shadow on the ground;
  - lighter tops and a darker shadowed side, from a fixed sun direction;
  - a little ground texture/noise, so the ground isn't flat colour bands.
- Keep the renderer deterministic, without fonts, Java2D only, under the 2 MB limit.
- Tests: the same input gives the same PNG; a photo facing a rock shows a rounded silhouette
  (e.g. its top edge is not one flat row).
