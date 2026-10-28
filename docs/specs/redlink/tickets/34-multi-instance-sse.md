# 34 — Multi-instance live updates (LISTEN/NOTIFY)

Status: ready-for-human

## Scope

- **Cross-instance SSE:**
  - `RoverEventPublisher` sends `pg_notify('rover_events', '<roverId>:<eventId>')` in the
    transaction of the event (delivered on commit only);
  - `RoverEventListener` (one LISTEN connection per instance, reconnecting) makes `SseHub.catchUp`
    send each stream of that rover the stored events it has not seen, in order;
  - the in-memory path stays; each stream's last sent id stops duplicates;
  - after a reconnect, every stream is caught up;
  - live catch-up has its own limit (1000), apart from the resume `replay-limit`;
  - `redlink.events.cross-instance=false` turns it off.
- **Login throttle in PostgreSQL:**
  - `login_failures` (V15);
  - one atomic upsert per failure (a new window when the old one expired);
  - expired rows are deleted;
  - shared by all instances.
- The PostgreSQL driver moves to compile scope (the LISTEN/NOTIFY API).
- ADR `docs/adr/multi-instance-listen-notify.md`.

## Acceptance criteria

- [x] An event committed by another instance (row + NOTIFY, no in-memory event) reaches this instance's open stream.
- [x] A local change is streamed once, not twice.
- [x] Login failures are counted across instances (two throttle objects, one database); window,
  retry-after, per-client keys, success reset and cleanup all tested.
- [x] The security slice still tests the HTTP answer (429, `Retry-After: 900`, problem type) with the
  throttle mocked.
- [x] Full `./mvnw verify -Plocal-db` green.

## Notes

- The submission rate limiter (ticket 31) stays per instance on purpose; see the ADR.

## Verification

- New tests:
  - `LoginThrottleIT` (4);
  - `EventsIT`: an event from another instance arrives over NOTIFY, and a local event is not
    duplicated;
  - the security slice was reworked to test the HTTP answer with the throttle mocked.
- Full `./mvnw verify -Plocal-db`: unit 77 + 101 + 36, integration 168 + 27, all green.
