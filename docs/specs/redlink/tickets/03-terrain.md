# 03 — Terrain: generated heightmap + loader

Status: ready-for-human
Source spec: ../SPEC.md §7

## Scope

One terrain definition that rover-sim (collisions, slopes, battery cost), mission-control (serves
it to the frontend) and the 3D scene all read, so the backend and the 3D world always match.

- Site = a folder `sites/<siteId>/` on the classpath of `redlink-contract`:
  - `site.properties`: id, name, grid size, meters per cell, elevation range, rover start cell.
    Properties instead of JSON keeps the contract free of a JSON library at runtime.
  - `heightmap.png`: 8-bit grayscale, `0` = lowest, `255` = highest elevation.
    A browser can load it directly as a texture for the 3D terrain mesh.
  - `surface.png`: 8-bit grayscale, `0` = ground, `128` = sand (costs more battery), `255` = rock
    (blocked).
- `Terrain` (immutable): `elevationAt`, `surfaceAt`, `isBlocked` (rock or outside the map),
  `slopeDegrees` between two cells.
- `TerrainLoader`: loads from the classpath or a directory and validates it (image sizes match,
  only known surface values, start cell inside the map and not blocked).
- `TerrainGenerator`: deterministic (seeded) generator with rolling noise, craters with raised
  rims, sand patches and scattered rocks (denser on crater rims), plus a clear, flat landing zone
  around the start cell. `main` writes a site folder.
- `TerrainWriter`: writes a site folder.
- Default site `jezero-sim` (64 × 64 m, 0–4 m elevation, seed 2021: the year Perseverance landed in Jezero) generated
  and committed.

Later the generated site can be swapped for real NASA elevation data (a HiRISE DTM of Jezero
converted to the same three files). Nothing else changes.

## Out of scope

Serving the heightmap over HTTP (ticket 08), movement rules that use slopes (ticket 06), 3D mesh (ticket 18).

## Acceptance criteria

- Same seed gives an identical terrain; a different seed gives a different one.
- Write → load round-trip is exact.
- The landing zone is clear and gentle; the default site loads from the classpath.
- The loader rejects mismatched image sizes, unknown surface values and a blocked start cell.
- Unit tests for bounds, slope and obstacle lookup.

## Checklist

- [x] `SiteInfo`, `SurfaceType`, `Terrain`
- [x] `TerrainLoader` + `TerrainWriter` + `InvalidTerrainException`
- [x] `TerrainGenerator`
- [x] Tests
- [x] Generate and commit `sites/jezero-sim`
- [x] SPEC §7 updated
- [x] `./mvnw -pl redlink-contract verify` green (73 tests); all modules compile

## Notes for later tickets

- Ticket 06: about 1.5 % of neighbouring steps on `jezero-sim` are steeper than 30° (crater walls)
  and 3 % steeper than 20°. A 30° limit (Curiosity's rating) makes craters real hazards without
  trapping the rover.
- Rock placement hashes (seed, x, y) instead of drawing from one `java.util.Random` stream: drawing
  per cell row by row produced visible columns of rocks (LCG stride correlation).
