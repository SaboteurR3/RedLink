# Missions: plan check with shared specs, derived status, ordered uplink

Status: accepted (ticket 17)

## Context

A mission is an ordered plan. Three questions: how Earth decides a plan is possible without
reading the rover's true state, where the mission's status lives, and how the order survives
approvals and failures.

## Decision

- **Shared spec sheet.** Costs and limits (battery per meter, max slope, drill minimum, storage
  per photo, ...) live in the contract as `RoverSpecs`. The rover's `FlightRules` execute with
  them; Earth's `MissionPlanner` simulates with them, from the last known state (telemetry) on the
  public terrain. Neither module depends on the other, and they cannot disagree about a number.
- **Known start.** A plan is only accepted when the rover has no unfinished commands (`409
  rover-busy`); otherwise the last known state is not where the plan begins. The rover row is
  locked (`FOR UPDATE`) during the check, so two plans cannot both pass it.
- **Infeasible is 422**, not 400: the request is well-formed, but cannot be carried out in the
  current state. The problem names the step (`step`, `errors`), and nothing is created.
- **Derived status.** The mission's status is computed from its steps (FAILED > CANCELLED >
  COMPLETED > IN_PROGRESS > QUEUED) and never stored. A stored copy would need updating from
  telemetry, approvals, cancels and uplink rejections, and could drift. The ETag is the sum of
  the steps' (only increasing) versions, so it changes exactly when any step changes.
- **Order on the uplink.** The transmitter only takes a mission step when no earlier step of the
  same mission is still on Earth. A step waiting for approval therefore holds the rest back.
- **Abort.** When a step ends badly, a synchronous Spring event (`CommandEnded`) lets the mission
  package withdraw the later Earth-side steps in the same transaction. The command package does
  not know about missions.

## Consequences

- Mission steps leave Earth one per transmitter tick (500 ms), not in one batch.
- Single commands may still be sent while a mission runs; the rover's own checks and safe mode
  protect it if they spoil the plan.
- Steps already transmitted cannot be recalled when an earlier step fails; the rover's fault
  protection handles them.
