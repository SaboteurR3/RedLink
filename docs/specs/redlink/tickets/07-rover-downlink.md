# 07 — rover-sim: downlink outbox + dispatcher

Status: ready-for-human
Source spec: ../SPEC.md §4

## Scope

Telemetry queued in `downlink_packets` (tickets 05, 06) is delivered to Earth after its light-time
delay, signed, at least once, and never by two dispatchers at the same time.

- **Claim with a lease** (one SQL statement): due `PENDING` packets are selected
  `FOR UPDATE SKIP LOCKED`, their `next_attempt_at` is pushed one lease (30 s) ahead and
  `attempts` incremented. Concurrent dispatchers get disjoint packets. The HTTP call happens
  outside any transaction, so a slow Earth never holds database connections or row locks. If the
  dispatcher dies mid-send, the lease expires and the packet is retried.
- **Send**: `POST {earth-url}/dsn/downlink` with the stored packet JSON, signed
  (`X-Link-Signature`), connect/read timeouts.
- **Outcome**:
  - `2xx` → `SENT`.
  - `400`, `409`, `413`, `422` → `REJECTED` (the packet itself is unacceptable; retrying cannot help).
  - everything else (network error, timeout, `401` during secret rotation, `404` while Earth is
    being deployed, `5xx`) → retry with exponential backoff 1 s, 2 s, 4 s … capped at 60 s, forever:
    store-and-forward, like DTN.
- Flyway `V3`: status `REJECTED`.
- `redlink.downlink.*` settings; dispatcher toggleable (tests drive it by hand).

## Out of scope

Earth's receiver `/dsn/downlink` (ticket 11) — until then rover-sim keeps retrying (404),
which is exactly the intended store-and-forward behaviour. Cleanup of old SENT rows.

## Acceptance criteria

- Not sent before `deliver_at`; sent after, with a valid signature over the exact body.
- 503 → retried with 1 s, then 2 s backoff, then SENT; 400 → REJECTED, never retried.
- Earth unreachable → retried.
- Two concurrent claims never return the same packet.
- A claimed packet whose sender died is due again after the lease.

## Checklist

- [x] Ticket detailed
- [x] V3 migration, `DownlinkPacket.Status.REJECTED`
- [x] `DownlinkProperties`, `DownlinkClaims` (claim / sent / retry / rejected)
- [x] `EarthLinkClient`
- [x] `DownlinkDispatcher` + scheduling
- [x] Integration tests with a stub Earth
- [x] SPEC §4 updated
- [x] `./mvnw verify -Plocal-db` green: 121 unit + 22 integration tests
- [x] Live check with both apps: mission-control accepts the rover's signatures (0 rejections), answers 404 (no endpoint until ticket 11), rover retries with backoff

## Notes for later tickets

- Ticket 10: mission-control's uplink dispatcher is the same pattern (claim with lease, send
  outside the transaction, classify the status). Use HTTP/1.1 there too.
- Ticket 11: 8 telemetry packets from the live checks are still queued in the dev `rover`
  database; they will be delivered as soon as `/dsn/downlink` exists.
- Found by the live check, not by the stub: the JDK HTTP client sends `Upgrade: h2c` on plain
  http, which Tomcat cannot handle for a POST with a body → every delivery failed with an I/O
  error. Pinned to HTTP/1.1; the stub now asserts no Upgrade header.
- Later: purge SENT rows older than some days (they only grow).
