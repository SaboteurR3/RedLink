# 05 — rover-sim: uplink endpoint + dedupe

Status: ready-for-human
Source spec: ../SPEC.md §4, §6

## Scope

The rover's receiving end of the uplink: a signed `CommandPacket` arrives on `POST /flight/uplink`
and is stored exactly once, however often the link delivers it.

- Flyway `V1__create_rover_tables.sql` (rover database):
  - `rover_state`: the rover's true state, seeded with rover 1 ("Perseverance-sim") at the
    `jezero-sim` start cell. Created here because the uplink must know which rovers exist; the
    executor that changes it is ticket 06.
  - `received_commands`: primary key `packet_id` (the dedupe key), unique
    `(rover_id, sequence_no)`, command stored as `jsonb`.
  - `downlink_packets`: the telemetry outbox (sent by ticket 07).
- `POST /flight/uplink` (link-signed, see ticket 04):
  - New packet → `202 Accepted` `{packetId, commandId, duplicate: false, receivedAt}`; in the
    same transaction a `COMMAND_STATUS RECEIVED` telemetry packet goes into the outbox with
    `deliver_at = now + one-way delay`.
  - Same packet again (link retry) → `202` with `duplicate: true`; nothing changes, no telemetry.
  - Same `packetId` with different content → `409` (`packet-conflict`).
  - Another packet already holds this `(rover, sequenceNo)` → `409` (`sequence-conflict`).
  - Unknown rover → `404` (`unknown-rover`).
  - Not a valid dictionary command (bad JSON, unknown type, out-of-range parameter) → `400`
    (`invalid-packet`). Earth must treat 4xx as permanent and not retry.
- Race-free dedupe: `INSERT ... ON CONFLICT DO NOTHING`, so concurrent deliveries of the same
  packet cannot both succeed or fail with a constraint error.
- Rover-wide Problem Details handler (typed 400s, catch-all 500 that leaks nothing).
- `/error` reachable again on rover-sim, so a 500 is not disguised as a 404.

### Test infrastructure (both apps)
- Unit tests (`*Test`) run in `test`; integration tests (`*IT`) run in `verify` (failsafe).
- ITs use Testcontainers PostgreSQL, or with `-Plocal-db` the local PostgreSQL (schema `it` in each
  app's own database, so dev data is untouched). Docker Desktop hangs on the dev machine.
- `*ApplicationTests` context checks become `*ApplicationIT`.

## Out of scope

Executing commands (06), sending telemetry (07), Earth-side dispatch (10).

## Acceptance criteria

- New packet: 202, stored once, one RECEIVED telemetry row due exactly one delay later.
- Repeated packet: 202 `duplicate: true`, still one row and one telemetry row.
- 20 concurrent deliveries of the same packet: one row, one telemetry row, all 202.
- Conflicts 409, unknown rover 404, invalid commands 400 with problem `type`s.
- All of it against real PostgreSQL.

## Checklist

- [x] Test infrastructure: failsafe, `local-db` profile, ITs renamed, container optional
- [x] Migration + entities + repositories
- [x] Telemetry outbox (`TelemetryOutbox.emit`)
- [x] `UplinkService` (dedupe, conflicts) + `UplinkController`
- [x] Problem handler + `/error`
- [x] Integration tests
- [x] SPEC §6, §9 updated
- [x] `./mvnw verify -Plocal-db` green: 102 unit + 10 integration tests

## Notes for later tickets

- Ticket 06: `rover_state` already exists (seeded rover 1); the executor only adds behaviour.
- Ticket 07: `downlink_packets.packet` holds the whole `TelemetryPacket`; sign and send its text.
- Ticket 10: rover answers 4xx only for permanent problems; retry only on network errors and 5xx.
- Found by the tests: Java timestamps have 100 ns precision on Windows, PostgreSQL microseconds;
  the first reply and a replay differed until `Instant`s were truncated before storing.
