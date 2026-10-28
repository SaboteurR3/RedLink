# 11 — mission-control: downlink receiver, lifecycle from telemetry

Status: ready-for-human
Source spec: ../SPEC.md §4, §5, §8 (telemetry)

## Scope

Earth receives the rover's telemetry and updates what it knows: command statuses and the rover's
last known state. (Cancel moved to ticket 09.)

### Receiver: `POST /dsn/downlink` (link-signed, ticket 04)
- Body: a `TelemetryPacket`, validated against the dictionary (invalid → 400).
- Stored in `telemetry` with `INSERT ... ON CONFLICT (packet_id) DO NOTHING`: a repeated delivery
  → `202 {duplicate: true}`, no effect. New → `202 {duplicate: false}`. Storing and applying share
  one transaction; a concurrent-update conflict → 412, which rover-sim retries.
- Unknown rover → `422` (rover-sim treats 422 as permanent; 404 would be retried forever).

### Applying telemetry (order is not guaranteed: retries reorder packets)
- `COMMAND_STATUS` → command status moves **forward only**:
  `TRANSMITTED < RECEIVED < EXECUTING (progress only rises) < COMPLETED | FAILED | REFUSED_BY_ROVER`.
  Older or repeated reports are ignored; final states never change. `finishedAt` = Mars time.
  Failure code/detail copied. Unknown command or command of another rover → stored, not applied.
- `ROVER_STATE` → the rover's last known state (position, heading, battery, temperature, storage,
  mode, `reportedAt`) if the report is newer (Mars time) than what Earth has; through the entity,
  so `version` and the ETag change.
- `EVENT` → stored (shown live in ticket 12).

### History: `GET /api/v1/rovers/{id}/telemetry?type=&from=&to=&page=&size=` (VIEWER)
- Newest first (Mars time), filters by type and Mars-time range; `from` after `to` → 400.

### Data
- Flyway `V5`: `telemetry` (packet_id unique, rover, type, command_id, payload jsonb, rover_time,
  received_at), indexes for history and per-command lookups.

## Out of scope

Live push (12), photos (16).

## Acceptance criteria

- RECEIVED → EXECUTING 50 → COMPLETED reflected on the command; reversed or repeated reports change nothing.
- FAILED / REFUSED carry the rover's failure code and detail.
- Duplicate packet: 202 duplicate, one row, no double effect.
- ROVER_STATE: newer applied (ETag changes, `reportedAt` set), older ignored.
- Unknown rover 422, invalid payload 400, unsigned 401.
- History: paging, type filter, time range, 400 for an inverted range.
- Live: the queued telemetry for command 1 reaches Earth and the command becomes COMPLETED.

## Checklist

- [x] Ticket detailed
- [x] V5 migration, `TelemetryRecord` entity + repository
- [x] `Command.applyRoverReport`, `Rover.applyTelemetry`
- [x] `TelemetryService` (dedupe, apply) + `DownlinkController`
- [x] History endpoint
- [x] Integration tests
- [x] SPEC updated
- [x] `./mvnw verify -Plocal-db` green: 138 unit + 92 integration tests
- [x] Live end-to-end: waiting telemetry moved command 1 to COMPLETED (finishedAt = Mars time); a new ROTATE went QUEUED → TRANSMITTED → EXECUTING → COMPLETED by itself and Earth's heading became EAST; older ROVER_STATE packets arriving later did not roll it back

## Notes for later tickets

- Ticket 12: publish SSE events after commit from `TelemetryService.receive` (command status
  changed, rover state changed, event) and from command submit/cancel/transmit.
- Ticket 20: start mission-control before rover-sim (compose `depends_on` with healthcheck);
  otherwise the first downlink attempts hit a closed port and back off up to 60 s. Harmless, but
  it delays the first telemetry in a demo.
- The JDK HTTP client reports "connection refused" with a null message ("I/O error ...: null");
  include the exception class in logs (done: `ResourceAccessException`).
