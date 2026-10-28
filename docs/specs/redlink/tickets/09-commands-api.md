# 09 — mission-control: commands API (202, Idempotency-Key, HATEOAS)

Status: ready-for-human
Source spec: ../SPEC.md §5, §8 (Commands, Idempotency rules)

## Scope

### Submitting a command
`POST /api/v1/rovers/{roverId}/commands` (OPERATOR), body = a command from the dictionary,
e.g. `{"type":"MOVE","distanceMeters":5}`.
- `202 Accepted` (the command is queued, not done), `Location: /api/v1/commands/{id}`,
  `Retry-After` (poll hint), body = the command with `_links`.
- `DRILL_SAMPLE` starts in `PENDING_APPROVAL`, everything else in `QUEUED`.
- Pre-checks against the **last known** state (Earth cannot know better): rover in safe mode and
  command is not REBOOT → `422 rover-in-safe-mode`; MOVE needs ≥ 1 % per meter → `422
  insufficient-battery`. Unknown rover → 404, invalid command → 400.

### Idempotency-Key (IETF draft-ietf-httpapi-idempotency-key-header)
- Required: missing or malformed (1–255 visible ASCII characters) → `400`.
- Scoped per user. Same key + same request (rover + canonical JSON body, so whitespace and
  field order don't matter) → the stored response replayed byte-for-byte, with
  `Idempotent-Replayed: true`. Same key + different request → `422 idempotency-key-reused`.
- One transaction: `INSERT ... ON CONFLICT DO NOTHING` on the key, then create the command and
  store the response. A concurrent duplicate waits on the key's row lock and then replays; a
  failed request (4xx/5xx) rolls back its key, so only successes are remembered.
- The key lookup comes before the state pre-checks: a retry of a request that succeeded replays
  the success even if the rover's state changed since.
- Records expire after 24 h (key reusable afterwards); an hourly job deletes them.

### Reading
- `GET /api/v1/commands/{id}` (VIEWER): `ETag` + `Cache-Control: no-cache, private`, 304 on
  `If-None-Match`. HATEOAS `_links`: `self`, `rover`, and `cancel` only while it is allowed.
- `GET /api/v1/rovers/{roverId}/commands?status=&page=&size=` (VIEWER): newest first.

### Cancelling (moved here from ticket 11: the `cancel` link must point at a real endpoint)
- `POST /api/v1/commands/{id}/cancel` (OPERATOR): `PENDING_APPROVAL`/`QUEUED` → `CANCELLED`
  (200); already cancelled → 200 again (safe to retry); anything later → `409
  command-not-cancellable` ("already on its way to Mars").

### Data
- Flyway `V3`: `commands` (status lifecycle, `sequence_no` assigned at transmission in ticket 10,
  `version`), `idempotency_records` (PK user + key, request hash, stored status/location/body as TEXT).

## Out of scope

Transmission (10), status from telemetry (11), approve/reject (14), missions (17).

## Acceptance criteria

- 202 + Location + Retry-After + links; DRILL → PENDING_APPROVAL.
- Missing/malformed key 400; replay identical with `Idempotent-Replayed`; reordered JSON replays;
  different body 422; keys are per user; 10 concurrent duplicates → 1 command, 10 identical 202s;
  expired key reusable.
- 422 pre-checks; replay still succeeds after the rover entered safe mode.
- GET with ETag/304; list newest first with status filter.
- Cancel: 200, idempotent, 409 once transmitted, 403 for VIEWER; `cancel` link appears only when allowed.

## Checklist

- [x] Ticket detailed
- [x] V3 migration
- [x] `CommandStatus`, `Command` entity, repository
- [x] Idempotency: key validation, `IdempotencyService`, purge job
- [x] `CommandService` (submit, get, list, cancel) + pre-checks
- [x] `CommandController` + DTOs with `_links`
- [x] Problem types (idempotency, 422s, 409)
- [x] Integration tests
- [x] SPEC §5, §8 updated; ticket 10/11 notes
- [x] `./mvnw verify -Plocal-db` green: 131 unit + 73 integration tests
- [x] Live check: 202 + Location + Retry-After, replay with Idempotent-Replayed, 422 on key reuse

## Notes for later tickets

- Ticket 10: cancel already exists. When the uplink outbox exists, cancel must also remove the
  command's not-yet-sent packet in the same transaction, or (simpler) the outbox row is written
  only when the dispatcher takes a QUEUED command; then cancel needs no change. Sequence numbers
  are assigned at that moment, per rover, from 1 without gaps (ticket 06 note).
- Ticket 10 live check: the dev `rover` database already has `last_sequence_no = 1` from the
  ticket 06 check, and dev mission-control has command 1 QUEUED. Reset rover 1 (last_sequence_no 0,
  delete received_commands/downlink_packets) before the end-to-end run, or sequence 1 is refused
  as SEQUENCE_EXPIRED.
- Ticket 11: cancel was moved into this ticket; ticket 11 keeps the lifecycle-from-telemetry part.
- Ticket 14: approve/reject links go into `CommandResponse._links` the same way as `cancel`.
- Errors: features now throw subclasses of `web.ApiException` (status, type, title); the global
  handler renders them, no new `@ExceptionHandler` needed.
