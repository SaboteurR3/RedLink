# 10 — mission-control: uplink outbox + dispatcher

Status: ready-for-human
Source spec: ../SPEC.md §4, §5

## Scope

Queued commands leave Earth, travel for one light-time, and reach the rover — signed, in order,
at least once.

### Transmission (`UplinkTransmitter`)
- A `QUEUED` command is held for `queue-hold` (5 s): the operator's last chance to cancel. Then,
  in **one transaction**: next per-rover sequence number (`uplink_sequences`, atomic
  `UPDATE ... RETURNING`), command → `TRANSMITTED` (`sequenceNo`, `transmittedAt`), and an
  `uplink_packets` row with the exact `CommandPacket` JSON, `deliver_at = now + one-way-delay` (10 s).
- Commands are picked `FOR UPDATE SKIP LOCKED`, oldest first: two transmitters never take the
  same command, and per-rover sequence numbers follow creation order without gaps (a cancelled
  command never got a number). `PENDING_APPROVAL` commands are never transmitted.
- Cancel needs no change: it races only with this transaction, and `@Version` lets one win.

### Delivery (`UplinkDispatcher`, same pattern as rover-sim's downlink, ticket 07)
- Claim due packets with a lease, `POST {rover-url}/flight/uplink` signed (`X-Link-Signature`),
  HTTP/1.1, timeouts, outside any transaction.
- 2xx (also the rover's `duplicate: true`) → `SENT`. 400/409/413/422 → `REJECTED`, and the
  command becomes `FAILED` (`UPLINK_REJECTED`). Everything else → retry 1 s, 2 s … 60 s, forever.
- The rover's 202 does **not** change the command's status: Earth learns RECEIVED only from
  telemetry (ticket 11).

### Data / config
- Flyway `V4`: `uplink_sequences` (per-rover counter, seeded), `uplink_packets` (packet as TEXT),
  partial index on queued commands.
- `redlink.uplink.*`: `rover-url` (no default outside `local`), hold, delay, poll interval,
  batch, lease, backoff, timeouts, `enabled` (tests drive it by hand).

## Out of scope

Telemetry receiver and statuses after TRANSMITTED (11), link windows/conjunction holds (19).

## Acceptance criteria

- Held for 5 s, then TRANSMITTED with sequence 1, packet due 10 s later; cancel now 409.
- Sequences 1, 2, … in creation order; a cancelled command uses no number; PENDING_APPROVAL never sent.
- Two transmitters at once: every command once, no duplicate sequence numbers.
- Packet not sent before `deliver_at`; then signed, body = the `CommandPacket`; 503 retried with
  backoff; 409 → REJECTED + command FAILED.
- Live: a command from the API makes the rover drive.

## Checklist

- [x] Ticket detailed
- [x] V4 migration
- [x] `Command.transmit`, `Command.uplinkRejected`
- [x] `UplinkProperties`, `UplinkTransmitter`
- [x] `UplinkClaims`, `RoverLinkClient`, `UplinkDispatcher`, scheduler
- [x] Integration tests (stub rover, mutable clock)
- [x] SPEC §4/§5 updated
- [x] `./mvnw verify -Plocal-db` green: 131 unit + 80 integration tests
- [x] Live end-to-end: command 1 (MOVE 3) transmitted as sequence 1, 10 s in flight, rover drove (32,32) → (32,29); Earth stays TRANSMITTED until ticket 11

## Notes for later tickets

- Found while writing the concurrency test: two transmitters with only `SKIP LOCKED` would take
  different batches and draw sequence numbers interleaved, so a newer command could run first on
  the rover. Transmission is now exclusive (`pg_try_advisory_xact_lock`); the test runs 3
  transmitters at once and checks sequence order = creation order.
- Ticket 11: telemetry for command 1 is queued in the dev `rover` database (and earlier packets
  from ticket 06/07 checks were deleted by the reset). Once `/dsn/downlink` exists, Earth should
  move command 1 to COMPLETED.
- Ticket 19: link windows / solar conjunction plug into `UplinkTransmitter.transmitDue` (hold
  commands while the link is closed).
