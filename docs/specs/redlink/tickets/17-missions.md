# 17 — Missions (batch plans)

Status: ready-for-human

## Scope

A mission is an ordered plan of commands for one rover. The whole plan is checked before
anything is queued, the steps go out in order, and when a step ends badly the steps still on
Earth are withdrawn.

- **Shared rover specs:** the costs and limits (battery per meter, max slope, drill minimum,
  storage per photo, and so on) move from rover-sim's `FlightRules` into the contract as
  `RoverSpecs`, the rover's published spec sheet. The rover executes with them and Earth plans
  with them, so the two cannot drift apart.
- **Plan check (`MissionPlanner`, pure):** simulates every step from Earth's last known state on
  the site's terrain, exactly like the rover would:
  - MOVE: map edge, rocks, slope ≤ 30°, battery per cell (sand costs more);
  - ROTATE: turns the shortest way;
  - TAKE_PHOTO and DRILL_SAMPLE: storage and battery, and DRILL needs ≥ 30 %;
  - CHARGE adds battery; REBOOT leaves safe mode;
  - safe mode accepts only REBOOT.

  The first impossible step gives `422 mission-plan-infeasible`, with `step` and `errors` naming
  it. A possible plan returns its `forecast`: the end position, heading, battery and storage.
- **Known start state:** a mission is accepted only when the rover has no unfinished commands.
  Otherwise Earth's last known state is not where the plan starts, and the answer is
  `409 rover-busy`.
- **API:**
  - `POST /api/v1/rovers/{id}/missions` (OPERATOR):
    - body `{name, steps: [command…]}`, 1–50 steps, name 1–100 characters;
    - `Idempotency-Key` required, with the same replay semantics as commands;
    - answers `202`, `Location`, `Retry-After`.

    All steps are created in one transaction with `mission_id` and `mission_step`. DRILL steps
    wait for approval as usual.
  - `GET /api/v1/missions/{id}` (VIEWER): the mission with its steps (`CommandResponse`s),
    aggregate `status`, `progress {completed, total}`, `forecast` and `_links`. The ETag changes
    whenever any step changes, and If-None-Match gives 304.
  - `GET /api/v1/rovers/{id}/missions` (VIEWER): paged, newest first.
  - `POST /api/v1/missions/{id}/cancel` (OPERATOR): withdraws every step still on Earth and
    answers 200; doing it again is also 200. If everything has already left Earth, the answer is
    `409 mission-not-cancellable`. `_links.cancel` appears only while it is possible.
- **Aggregate status** is derived from the steps and never stored, so there is a single source
  of truth:
  - `FAILED` if any step failed, was refused or was rejected;
  - otherwise `CANCELLED` if any step was cancelled;
  - otherwise `COMPLETED` if all steps completed;
  - otherwise `IN_PROGRESS` if any step has left Earth;
  - otherwise `QUEUED`.
- **Order on the uplink:** a mission step is transmitted only when every earlier step of the
  same mission has left Earth or finished. A step waiting for approval therefore holds back the
  rest, and the rover never runs the plan with a hole in it.
- **Abort:** when a step ends as FAILED, REFUSED_BY_ROVER, REJECTED or CANCELLED, the steps
  still on Earth become CANCELLED with failure code `MISSION_ABORTED`. A Spring event from the
  command side triggers this in the same transaction. Steps already transmitted cannot be called
  back; the rover's own fault protection covers them.
- **Commands** show `mission {id, step}` and `_links.mission`.
- **Frontend:** a mission planner in the console (add steps, name, submit; shows the forecast or
  the infeasible step), and timeline chips "M3·2/4".

## Acceptance criteria

- [x] Planner: a feasible plan gives the forecast; a rock, the edge, a steep slope, an empty
      battery, too little battery to drill, full storage, or safe mode without REBOOT each name
      the right step.
- [x] POST: 202 + Location + Retry-After; steps created in order with DRILL steps PENDING_APPROVAL;
      replay with the same key gives the identical body + `Idempotent-Replayed`; the same key with
      another plan gives 422; a missing key gives 400.
- [x] 422 infeasible (nothing created), 409 rover-busy, 400 for 0 or 51 steps or an invalid
      step, 403 for VIEWER, 404 for an unknown rover.
- [x] Uplink: a later step is not transmitted while an earlier one waits for approval; it is
      after approval.
- [x] A failed, rejected or cancelled step cancels the later Earth-side steps (MISSION_ABORTED).
- [x] GET mission: aggregate status, progress, ETag changes when a step changes, 304.
- [x] Cancel: 200, again 200, 409 when everything has been transmitted.
- [x] Frontend planner works; the build and its tests pass.
- [x] `./mvnw verify -Plocal-db` green.

## Checklist

- [x] Contract `RoverSpecs`; rover-sim `FlightRules` uses it
- [x] V10 migration: `missions`, `commands.mission_id/mission_step`
- [x] `MissionPlanner` + unit tests
- [x] `Mission` entity/repository, `MissionService`, `MissionController`, problem types
- [x] Command: mission fields in entity/response; `CommandEnded` event at every unsuccessful end
- [x] Uplink order gate
- [x] Abort listener
- [x] `MissionsIT`
- [x] Frontend planner + chips + tests
- [x] SPEC, README index, ADR
- [x] Full build green

## Notes for later tickets

- 18 (3D scene): the mission `forecast` (end cell and heading) can be drawn as a ghost rover, and a
  plan's path could be previewed with the same simulation if a dry-run endpoint is added.
- 19 (random events): dust storms change charging gain; `RoverSpecs` then describes the nominal
  rate and the planner's forecast is "nominal", which the README should say.
- 21 (write-up): ADR `missions-derived-status-and-shared-specs` explains the design choices.

## Verification

- `./mvnw verify -Plocal-db`: 193 unit tests (contract 75, mission-control 87, rover-sim 31) and
  154 integration tests (mission-control 132, rover-sim 22), all green. Frontend: 25 tests, build OK.
- Live run with both apps: a mission was refused with `409 rover-busy` while command 9 (left
  TRANSMITTED by the previous session) was in flight. Once it completed, the 3-step mission (photo,
  rotate west, photo) got 202, went out as sequence 9, 10, 11, ended COMPLETED 3/3, and the rover's
  reported end state equalled the forecast: (34, 29) facing WEST, 83.9 % battery, 15 % storage.
