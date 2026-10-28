# 39 — Bigger drivable sites (128 × 128 m)

Status: ready-for-human

## Scope

The rover could drive on only 64 × 64 m, while the 3D scenery reaches about 700 m. The user chose
128 × 128 m: four times the area.

- **Generator:**
  - both shipped sites become 128 × 128 cells (1 m each);
  - the ground keeps the same detail per meter: the noise frequency, the number of craters and the
    sand patches scale with the site's size;
  - a little more relief (Jezero 0–5 m, Gale 0–6 m);
  - start cells (64, 64) for Jezero and (40, 80) for Gale, rock-free and flat as before;
  - files regenerated, and `TerrainLoaderTest` still checks they match the generator.
- **Data:** the rovers' stored positions belong to the old maps, so new migrations put each
  rover back on its site's start cell, facing north:
  - mission-control: what Earth last knew;
  - rover-sim: the rover's true state.
  - Old telemetry, photos and samples keep their coordinates; they are history of the old map.
- **Tests:** fix every test that assumed 64 × 64 or a cell on the old map.
- **Frontend:** check that the 2D map, the minimap, the 3D scene (shadows, dust, fog, scenery
  join) and the photos work at the new size.

## Acceptance criteria

- [x] Both sites are 128 × 128, start cells free, the shipped files match the generator.
- [x] After the migrations both rovers stand on their start cells in both databases.
- [x] Routes, missions, photos and drills work on the new maps (ITs).
- [x] The 3D view and the 2D map look right at 128 × 128 (screenshots), with a smooth frame rate.
- [x] `./mvnw verify -Plocal-db` green; Vitest, type check, build green.

## Checklist

- [x] Generator sizes, starts and scaling; regenerate both sites
- [x] Migrations: mission-control and rover-sim
- [x] Fix tests
- [x] Frontend check and fixes
- [x] SPEC, GUIDE, index

## Verification

- Generated maps checked visually: rolling ground, about 15 craters per site with rocky rims, sand
  patches, scattered rocks; start cells clear.
- Tests fixed:
  - `TerrainLoaderTest` wrote 64 × 64 images; it now uses the site's size;
  - `SitesIT` now expects 128, the new elevation ranges and the new start cells.
  - Every other test, including routes, missions, photos, drills and the rover ITs, passed on the
    new maps unchanged.
- Full `./mvnw verify -Plocal-db`: contract 81, mission-control 101 + 169, rover-sim 45 + 32.
- Browser, with my own mission-control on 8095:
  - the 3D view (about 120 fps on the GPU), the scenery join, the minimap, and the 2D map
    ("128×128 m", rover on (64, 64)) all look right.
  - To make this possible, Vite's proxy target can now be set with `REDLINK_API_URL` (default
    8080).
- Flaky test seen once: `LinkIT.commandsLeaveEarthOnlyWhileARelayWindowIsOpen` failed in one full
  run. It passed 3/3 alone and in the next full run. It is order-dependent (probably a background
  uplink tick racing the test's mutable clock), not about the maps; worth a look later.
