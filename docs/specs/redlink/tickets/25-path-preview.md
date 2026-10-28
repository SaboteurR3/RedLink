# 25 — Path preview (dry run)

Status: ready-for-human

## Scope

See a plan's path and outcome **before** sending it, so a drive into a rock is never sent by
mistake.

- `POST /api/v1/rovers/{id}/plan-previews` (VIEWER), body `{steps: [command…]}`, 1–50 steps.
  - It changes nothing, so it can be called on every keystroke. It is POST only because the plan
    travels in the body, answers `200` (nothing created) with `Cache-Control: no-store`, and needs
    no `Idempotency-Key`.
  - The answer: `feasible`; `path`, every position (start = step −1, each meter of a drive, each
    other step); `end`; `problem {step (one-based), reason, x, y}`, where x/y is the cell a failing
    drive would enter; `marsSeconds`, working time without charging; `chargeMinutes`; `roverBusy`,
    true when unfinished commands mean the real start may differ.
- `MissionPlanner.simulate(...)` gives the plan check with a trace. `plan(...)` is unchanged and
  delegates to it, so the mission check and the preview can never disagree.
- **Frontend:**
  - the console sends a debounced dry run (350 ms) of the plan, or of the current command, and
    shows "Path checked: ends at … battery …" or "Step N would fail: …";
  - the 3D map draws the path as a dashed line on the terrain (green, or amber when it fails), a
    ring where it ends, and a pulsing red box on the failing cell;
  - the 2D map draws the same as SVG.

## Acceptance criteria

- [x] Preview: path, end, time, problem with the failing cell; nothing created; `no-store`.
- [x] Console shows the verdict; 3D and 2D draw the path.
- [x] Tests: `MissionPlannerTest` (simulation), `MissionsIT` (preview feasible / failing).
- [x] Full build green; browser check.

## Verification

- `./mvnw verify -Plocal-db`: 211 unit + 179 integration tests green.
- Browser (headless Edge): choosing MOVE 3 showed "Path checked: ends at (23, 38) facing SOUTH,
  battery 22.5 %", and the 2D map drew the path.
