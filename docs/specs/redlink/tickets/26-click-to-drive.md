# 26 — Click-to-drive with route planning

Status: ready-for-human

## Scope

Click a cell on the map; RedLink plans the drive there and offers to send it.

- **`RoutePlanner`** (pure) runs A* over (cell, heading):
  - moving one cell forward costs its battery (ground 1 %, sand 2 %), and a quarter turn costs
    0.2 %, plus a tiny penalty so equal routes prefer fewer turns;
  - rocks, the map edge and slopes over 30° are never entered;
  - the heuristic (Manhattan distance × the ground cost) never overestimates, so the route found is
    the cheapest;
  - the result is compressed into the rover's dictionary: turns become one ROTATE to the final
    heading, and straight runs become MOVEs of at most 20 m.
- `POST /api/v1/rovers/{id}/routes` (VIEWER), body `{x, y}`:
  - answers `200 {steps, preview}`; the preview is the same dry run as ticket 25, so battery and
    storage are checked too;
  - `422 no-route` when the cell is a rock, outside the map, walled in, is where the rover already
    is, or needs more than 50 commands;
  - nothing is sent, and the answer is `no-store`.
- **Frontend:**
  - click the 3D terrain (drags of the camera are ignored; the cursor is a crosshair) or the 2D
    map;
  - a pulsing target ring and the route's path appear, and a panel shows the steps, the time on
    Mars and the battery afterwards;
  - **Drive there** (operators and directors) sends the steps as the mission "Drive to (x, y)";
    the ghost rover shows the end, and a toast confirms.

## Acceptance criteria

- [x] Cheapest route around rocks; turns first; long drives split; unreachable → empty / 422.
- [x] The route is a plan the mission planner accepts.
- [x] Click in 2D and 3D; panel; Drive there sends a mission.
- [x] Tests: `RoutePlannerTest` (6), `MissionsIT` (route + 422 cases).
- [x] Full build green; browser check.

## Verification

- `./mvnw verify -Plocal-db`: 211 unit + 179 integration tests green.
- Browser (headless Edge, against both apps):
  - a 2D click at (26, 38) planned "MOVE 3 m → ROTATE → EAST → MOVE 3 m · 8 s on Mars · battery
    after 19.3 %"; **Drive there** sent it (toast "Mission #… sent"), and the panel closed;
  - a 3D click picked (27, 40), with the dashed path around the corner and the target ring drawn
    on the terrain.
