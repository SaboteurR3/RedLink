# 06 — rover-sim: executor, rover state, fault protection

Status: ready-for-human
Source spec: ../SPEC.md §3 (command dictionary), §6

## Scope

The rover's flight software runs the commands it received, one step at a time, against the
terrain, and reports every step to Earth.

### Flight rules (pure, `FlightRules`)
| Command | Steps × duration | Effect per step | Fails when |
|---|---|---|---|
| MOVE n | n × 1 s | one cell forward; battery −1 % (ground) / −2 % (sand) | rock or map edge ahead (OBSTACLE), slope > 30° (SLOPE_TOO_STEEP), not enough battery (BATTERY_EXHAUSTED) |
| ROTATE h | quarter turns × 2 s | 90° the shortest way; −0.2 % | — |
| TAKE_PHOTO | 1 × 3 s | storage +2 %, −0.5 % | storage full (STORAGE_FULL) |
| DRILL_SAMPLE | 1 × 15 s | storage +5 %, −8 % | battery < 30 % (LOW_BATTERY), storage full |
| CHARGE m | m × 1 s (one simulated minute each) | +1 %, max 100 % | — |
| REBOOT | 1 × 5 s | mode → NOMINAL | — |

- **Fault protection**: hazards (OBSTACLE, SLOPE_TOO_STEEP, BATTERY_EXHAUSTED) stop the rover in
  SAFE_MODE; resource problems (STORAGE_FULL, LOW_BATTERY) only fail the command.
- In SAFE_MODE every command except REBOOT is REFUSED (`SAFE_MODE`).

### Ordering
- Commands run strictly in `sequenceNo` order; `rover_state.last_sequence_no` remembers the last one.
- A gap (number 2 missing, 3 present) is waited for up to `reorder-timeout` (30 s), then skipped
  with an EVENT `SEQUENCE_GAP_SKIPPED`; a packet arriving after its gap was skipped is REFUSED
  (`SEQUENCE_EXPIRED`). Without the timeout one lost packet would stall the rover forever.

### Executor
- Scheduled every `tick` (250 ms); per rover, one transaction does at most one action: start the
  next command, or perform the next step when it is due. `@Version` on `rover_state` guards
  against concurrent writers. Disabled in tests, which drive it with a controllable clock.
- Telemetry per action: COMMAND_STATUS (EXECUTING with progress, COMPLETED, FAILED, REFUSED),
  ROVER_STATE after every change, EVENT for safe mode and skipped gaps.
- Flyway `V2`: `rover_state.last_sequence_no`; `received_commands.total_steps`, `steps_done`,
  `next_step_at`.

## Out of scope

Photos as images (16), dust storms (19), sending telemetry (07).

## Acceptance criteria

- Unit: every rule above, rotation picks the shortest way, battery never negative.
- Integration (real DB, controlled clock): MOVE 2 completes after 2 s with the right telemetry;
  out-of-order arrival executes in order; a gap is skipped after the timeout; driving into a rock
  enters SAFE_MODE, the next command is REFUSED, REBOOT recovers.

## Checklist

- [x] Ticket detailed
- [x] `FlightRules`, `RoverSnapshot`, `StepOutcome`, `FailureCode` + unit tests
- [x] V2 migration, entity behaviour, repository queries
- [x] `TerrainRegistry`, `RoverProperties`
- [x] `CommandStepper` (ordering, refusal, steps, telemetry)
- [x] Scheduler (toggleable)
- [x] Integration tests
- [x] SPEC §6 updated
- [x] `./mvnw verify -Plocal-db` green: 121 unit + 15 integration tests
- [x] Live check: real signed MOVE 3 over HTTP, scheduler drove (32,32) → (32,29) in 3 s, 8 telemetry packets queued

## Notes for later tickets

- Ticket 10: Earth must number commands per rover from 1 without gaps in the order it transmits
  them; the rover waits up to 30 s for a missing number, then skips it.
- Ticket 11: several telemetry packets of one step share the same `roverTime` (e.g. ROVER_STATE and
  COMMAND_STATUS). Earth must not rely on arrival order between them; a command's status only moves
  forward (EXECUTING progress never decreases, final states are final).
- Java 17 has no pattern matching for `switch`; switch on `command.type()` to keep exhaustiveness.
