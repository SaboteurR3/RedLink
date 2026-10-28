# Several mission-control instances: LISTEN/NOTIFY for live updates, login throttle in the database

Status: accepted (ticket 34)

## Context

Two pieces of state lived only in one instance's memory:
- **Live updates:** `SseHub` sent an event to the streams open on the instance where the change
  committed. Behind a load balancer, a browser's stream is on instance A, while the telemetry packet
  that moves the rover may land on instance B. The browser would not see it until it reconnected.
- **Login throttle:** failed logins were counted per instance. With N instances an attacker gets N
  times the guesses.

## Decision

- **PostgreSQL LISTEN/NOTIFY** for live updates, since the database is already shared and already
  holds every event (`rover_events`, the source for resume):
  - In the same transaction as the event row, the publisher calls `pg_notify('rover_events',
    '<roverId>:<eventId>')`. NOTIFY is transactional: delivered only on commit, never for a
    rollback, which are the same semantics as the in-memory after-commit event.
  - Each instance holds one connection that LISTENs (`RoverEventListener`). A notification makes
    the hub **catch up** that rover's streams: it sends, in order, every stored event newer than the
    stream's last sent id.
  - Only ids travel, which avoids NOTIFY's 8000-byte payload limit; the data is read from the table.
  - The in-memory path stays for the local instance (no added latency). Each stream remembers its
    last sent id, so an event arriving both ways is sent once.
  - If the listen connection breaks, the listener reconnects and catches every stream up, so
    nothing committed in between is lost.
  - Live catch-up has its own limit of 1000. A resuming client's `replay-limit` (after which it
    gets `reset`) is for clients that were away.
- **Login throttle in a table** `login_failures` (username, client IP, window start, failures),
  keyed on both as before:
  - One atomic `INSERT … ON CONFLICT DO UPDATE` counts a failure and starts a new window when the
    old one has expired, so concurrent failures on several instances are all counted.
  - Expired rows are deleted on the way.

Not used:
- **Redis pub/sub:** a new piece of infrastructure for what PostgreSQL already does at this scale.
- **Sticky sessions:** they do not help, because the change that should be streamed arrives on
  whichever instance the rover's downlink reaches.

## Consequences

- Each instance holds one database connection permanently (out of Hikari's pool of 10).
- Notifications are not durable. That is fine because the table is: catch-up reads from it.
- The submission rate limiter (ticket 31) is still per instance. Its effect is only to slow a
  runaway client down, so a per-instance budget is an acceptable bound. The login throttle protects
  passwords, so it had to be shared.
- `redlink.events.cross-instance=false` turns the listener off (single instance, or a database
  without LISTEN support).
