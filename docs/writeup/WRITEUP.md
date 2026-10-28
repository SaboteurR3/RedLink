# RedLink — REST and web-service design on a deep-space link

RedLink is mission control for a simulated Mars rover. Operators on Earth send commands through a
REST API. The commands travel to the rover over a signed link with a real light-time delay, the
rover executes them on a terrain map, and the results come back as delayed telemetry.

The delay is the point. A Mars link cannot use "call and wait for the answer", so every classic
web-service problem shows up in its pure form: asynchronous processing, retries, duplicates,
ordering, conflicting writes, push, and errors that must be machine-readable. This document goes
through the nine design questions from the project spec (§14). For each one it gives:

- the problem;
- the decision;
- where it lives in the code;
- what it looks like on the wire;
- the alternatives;
- the tests that prove it.

Decision records with more detail are in [`docs/adr/`](../adr), the full specification is in
[`docs/specs/redlink/SPEC.md`](../specs/redlink/SPEC.md), and the build history is in
[`docs/specs/redlink/tickets/`](../specs/redlink/tickets).

## The system on one page

```
Browser (React, 3D)  ──REST + SSE──►  mission-control (Earth, :8080) ──signed HTTP──► rover-sim (Mars, :8081)
                     ◄──────────────   PostgreSQL "mission_control"  ◄──signed HTTP──  PostgreSQL "rover"
```

| Module | Role |
|---|---|
| `redlink-contract` | Command and telemetry dictionaries (sealed interfaces + records), `PacketSigner`, terrain files, `RoverSpecs`. No Spring. |
| `redlink-mission-control` | The public API: users and roles, commands, missions, approvals, photos, webhooks, the event stream, the uplink outbox, the downlink receiver. |
| `redlink-rover-sim` | The rover's flight software: receives packets, executes commands step by step on the terrain, renders photos, random events, the telemetry outbox. |
| `frontend` | React + TypeScript; 2D map and a React Three Fiber 3D scene that only moves on events from the API. |

Earth and Mars share no database and no code beyond the contract. Mission control never reads the
rover's true state; it only knows what telemetry reported, as in a real mission.

Stack: Spring Boot 4.1 (Java 17), Spring Security 7, Hibernate 7, Flyway, PostgreSQL, springdoc
OpenAPI 3.1, React 19, Vite, three.js. Lombok for boilerplate.

Tests:
- 203 unit tests and 175 integration tests (JUnit), the integration tests against a real
  PostgreSQL, never H2;
- 33 frontend tests;
- one end-to-end script ([`scripts/e2e.mjs`](../../scripts/e2e.mjs)) that drives the running
  system through its public URL.

---

## 1. Synchronous vs asynchronous REST

**Problem.** A command takes 10 s (real Mars: 3–22 min) just to arrive, then runs for a while, then
the result takes as long to come back. A synchronous `POST` that waits for the result would hold a
connection for minutes, fail on every proxy timeout, and still not know what happened after a
disconnect.

**Decision.** `POST /api/v1/rovers/{id}/commands` answers at once with **`202 Accepted`**:

- `Location: /api/v1/commands/{id}` names a status resource for the command;
- `Retry-After: 5` says when polling becomes worthwhile;
- the body is the command in its current state (`QUEUED`, or `PENDING_APPROVAL` for a drill).

From then on the command resource is the single truth about its lifecycle:

```
PENDING_APPROVAL → QUEUED → TRANSMITTED → RECEIVED → EXECUTING → COMPLETED | FAILED | REFUSED_BY_ROVER
                 ↘ REJECTED   ↘ CANCELLED (only while still on Earth)
```

The statuses after TRANSMITTED come from the rover's telemetry. Telemetry may arrive repeated or out
of order, so the lifecycle **only moves forward** (`Command.applyRoverReport`, `lifecycleRank`): a
late "EXECUTING 33 %" after "COMPLETED" is stored but changes nothing. Missions
(`POST /rovers/{id}/missions`) follow the same pattern for a whole plan: 202, `Location:
/missions/{id}`, and a mission status derived from its steps.

**Alternatives.**
- A synchronous call with a long timeout breaks at the first proxy.
- A callback URL per request is in effect a webhook (chapter 5).
- Long polling on the status resource would work, but SSE does the same job better (chapter 5).

**Tests.**
- `CommandsIT` checks the exact 202, `Location` and `Retry-After`.
- `TelemetryIT` checks forward-only application of out-of-order and late reports.
- `CommandLifecycleTest` checks the transition rules.
- `MissionsIT` checks the mission resource.

## 2. Idempotency

**Problem.** The client's connection drops after it sent a command. Did the server get it? If the
client retries, the rover may drive twice. A drive into a crater is not idempotent.

**Decision: `Idempotency-Key` on every command and mission `POST`** (following the IETF
httpapi draft).

- The header is required; a missing or malformed key is `400 invalid-idempotency-key`.
- The key is stored per user in `idempotency_records`, **in the same database transaction** as the
  command itself. Either both exist or neither does, so there is no window where the command is
  created but the key is not.
- The first response is stored as text and replayed byte for byte, with `Idempotent-Replayed: true`.
  It is TEXT, not JSONB, because JSONB would reorder the keys.
- The same key with a different body (a SHA-256 fingerprint of the canonical request) is
  `422 idempotency-key-reused`. A second request with the key while the first is still running is
  `409 idempotency-key-in-progress`.
- The key is checked **before** the business rules. A retry of a request that succeeded therefore
  replays the success, even if the rover has meanwhile entered safe mode and a fresh request would
  now get 422.

The frontend creates the key once per user action and reuses it on every retry
(`ApiClient.postIdempotent`), so a flaky connection can never send a command twice.

**The link is idempotent in both directions too.** The uplink and the downlink are both
*at-least-once* (see chapter 3), so both receivers deduplicate:

- the rover with `INSERT … ON CONFLICT DO NOTHING` on the packet id;
- Earth on the telemetry packet id.

A repeated delivery answers `202 {"duplicate": true}`, so the sender stops retrying. The same
packet id with different content is `409`, because it is a bug and not a retry. Webhook receivers
get an `X-RedLink-Delivery` id for the same purpose.

**Tests.**
- `CommandsIT` covers byte-for-byte replay, the same request with different formatting, keys per
  user, a failed request not being remembered, the replay winning over a changed rover state,
  concurrent duplicates creating exactly one command, and the missing or malformed key (400).
- `MissionsIT` covers mission replay and the same key with another plan (422).
- `UplinkIT` (rover) and `TelemetryIT` cover duplicate deliveries and the 409 conflict.
- The client tests check that a retry reuses the key.

## 3. Transactional outbox and store-and-forward

**Problem.** "Save the command, then send it" fails in two ways:

- the send succeeds but the transaction rolls back (the rover runs a command Earth has no record
  of);
- the transaction commits but the process dies before sending (the command is lost).

On top of that, the other side may be down for hours: a Mars link is **disrupted by nature**.

**Decision: a transactional outbox on both sides.** It works like the bundle protocol of
delay-tolerant networking (DTN): keep everything, forward when possible.

- **Earth → Mars.** A command waits `QUEUED` for a 5 s hold, the last chance to cancel. Then
  `UplinkTransmitter` does three things in one transaction:
  1. takes the next per-rover sequence number;
  2. marks the command `TRANSMITTED`;
  3. writes an `uplink_packets` row whose `deliver_at` is now plus the light time.

  The transmitter is exclusive cluster-wide (`pg_try_advisory_xact_lock`); otherwise two instances
  could hand out interleaved sequence numbers.
- **Mars → Earth.** Every telemetry packet is written into `downlink_packets` **in the same
  transaction as the change it reports** (`TelemetryOutbox.emit`, `Propagation.MANDATORY`). A
  packet exists exactly when its change was committed.
- **Dispatchers** (`UplinkDispatcher`, `DownlinkDispatcher`) claim due packets with a lease:

  ```sql
  UPDATE … SET next_attempt_at = :leaseUntil WHERE id IN (… FOR UPDATE SKIP LOCKED) RETURNING …
  ```

  They send them **outside** any transaction and record the outcome.
  - 2xx: sent.
  - 400/409/413/422: permanently rejected; never resent, and the command becomes FAILED.
  - Anything else (network error, 5xx, 404 during a deploy, 401 during a key rotation): retried
    with exponential backoff, 1 s → 60 s.

  A dispatcher that crashes after claiming only holds its packets until the lease ends; then they
  go again. With the receivers deduplicating (chapter 2), the result is *effectively once*.
- **Ordering.** The rover executes strictly by sequence number. A missing number is waited for
  30 s, then skipped (EVENT `SEQUENCE_GAP_SKIPPED`); if it turns up later it is refused
  `SEQUENCE_EXPIRED`. Mission steps are only transmitted after every earlier step has left Earth,
  so a step held for approval holds back the rest of its plan.
- **Disruption as a feature** ([ADR](../adr/link-availability-503-retry-after.md)):
  - Relay-orbiter windows close the uplink on a schedule.
  - A solar conjunction (switched by a flight director) holds every command on Earth, and makes
    Earth answer the rover's telemetry with **`503` + `Retry-After: 30`**. The rover's dispatcher
    honours `Retry-After` (seconds or HTTP-date, capped at 1 h) instead of its own short backoff,
    and does not hammer Earth with the rest of the batch. Nothing is lost on either side.

**Alternatives.**
- Two-phase commit across two databases and HTTP is not possible.
- A message broker (Kafka, RabbitMQ) would move the outbox into another system. It would still need
  an outbox to be transactional with the business write, which is why PostgreSQL alone is enough
  here.
- `@Async` or an in-memory queue loses everything on a crash.

**Tests.**
- `UplinkIT` and `DownlinkIT` (against stub servers) cover light time, backoff, 4xx vs 5xx,
  unreachable peers, concurrent dispatchers never claiming the same packet, re-sending after a
  crash once the lease expires, and Retry-After.
- `CommandExecutionIT` covers ordering, gaps and expiry.
- `LinkIT` covers windows and the conjunction.

## 4. Concurrency control

**Problem.** Two operators edit the same rover, two flight directors decide the same command, the
transmitter takes a command at the very moment an operator cancels it, and two dispatchers want the
same packets.

**Decision: three tools, each where it fits.**

| Tool | Where | What the client sees |
|---|---|---|
| **`ETag` / `If-Match`** (optimistic concurrency over HTTP) | `PATCH /rovers/{id}` (JSON Merge Patch), `PUT /link/conjunction` | Without `If-Match` on the PATCH: `428 Precondition Required`. Stale tag: `412 Precondition Failed`. The conjunction PUT sets an absolute value, so there `If-Match` is optional. |
| **`@Version`** (optimistic locking in the database) | every entity that changes | Catches the race *between* the `If-Match` check and the update; that also becomes 412, the same answer as a stale tag. |
| **`FOR UPDATE SKIP LOCKED`** | outboxes, uplink transmitter | Workers never wait for each other or take the same row. The transmitter's row lock means a concurrent cancel waits and then fails its version check, so a command is never both cancelled and transmitted. |

**`ETag` / `If-None-Match` for reads.**
- Rovers, commands, missions, the conjunction, photos and site maps carry strong ETags.
- `If-None-Match` gives `304 Not Modified` with no body.
- Strong tags (`"7"`) match strongly; `W/"7"` never satisfies `If-Match` (RFC 9110 §13.1.1).
- A mission's tag is the sum of its steps' versions. Versions only grow, so the tag changes exactly
  when any step changes.

The end-to-end run polls a running mission with `If-None-Match` and gets 28–29 `304`s while
waiting.

**Other races handled by design.**
- *Four-eyes rule.* Approving your own drill command is `403 four-eyes-violation`. Repeating a
  decision is 200; contradicting one is `409`.
- *Planning a mission from a known state.* While the rover has unfinished commands, the answer is
  `409 rover-busy`. During the check the rover row is locked (`FOR UPDATE`), so two plans cannot
  both pass.

**Tests.**
- `RoversIT` covers 428, 412, a concurrent PATCH and 304.
- `ApprovalsIT` covers four-eyes, repeated and contradicting decisions.
- `UplinkIT` covers that a transmitted command cannot be cancelled, and that sequence order equals
  creation order even with concurrent transmitters.
- `EntityTagsTest` covers the RFC 9110 comparison rules.
- `MissionsIT` covers the mission ETag.
- `LinkIT` covers the conjunction ETag and 412.

## 5. Push vs pull

**Problem.** The UI must show the rover moving the moment telemetry arrives, and other systems want
to know when a command completes. Polling every second is wasteful and still late.

**Decision: three mechanisms, chosen by who is listening.**

- **Polling with conditional GET** for simple clients. Every status resource supports
  `If-None-Match`, so polling costs a 304 when nothing changed.
- **Server-sent events** for the browser, on `GET /rovers/{id}/events`:
  - The stream sends `rover` (a snapshot first), `command`, `uplink` (with `arrivesAt`, which drives
    the 3D signal beam), `rover-event`, `photo`, `link` and `reset`.
  - Events are written to `rover_events` in the **same transaction** as the change (an outbox again)
    and pushed **after commit**, so a browser never sees a change that was rolled back.
  - Every event has an `id:`. A reconnecting client sends `Last-Event-ID` and gets exactly what it
    missed. If it was away too long it gets a `reset` with a fresh snapshot.
  - There is a heartbeat every 15 s, and `503` when the server holds too many streams.
  - `EventSource` cannot send an `Authorization` header, so the browser first gets a **single-use
    stream ticket** (`POST /rovers/{id}/events/tickets`: 30 s, bound to the rover, `no-store`). It
    passes the ticket in the URL, where a JWT must never go.
- **Webhooks** for other systems:
  - Events: `command.completed`, `command.failed`, `rover.safe_mode`, `rover.battery_low`.
  - Deliveries are written to an outbox in the transaction of the change, then sent with backoff.
  - Each delivery is signed with a per-webhook secret (`X-RedLink-Signature: t=…,v1=…`, the
    Stripe/GitHub scheme) and carries `X-RedLink-Delivery` for receiver dedupe.
  - The secret is shown once, at creation (`201`, `Cache-Control: no-store`).
  - The URL is checked against **SSRF**: https only, no credentials, and every resolved address
    must be public, checked again at send time against DNS rebinding. Redirects are never followed.
  - A receiver that answers `410 Gone` deactivates the webhook.

**Why not WebSocket?** The data only flows one way (server to browser). SSE is plain HTTP, so
proxies, compression and authentication work as usual, and it has resume (`Last-Event-ID`) built
in. Commands go up through the REST API, where idempotency, validation and roles already live.

**Tests.**
- `EventsIT` covers the snapshot, a failed request never being streamed, resume (header and query
  parameter), reset, and tickets (uncacheable, single use, expiry, wrong rover). The 503 for too
  many streams is not covered by a test.
- `WebhooksIT`, `WebhookSsrfIT` and `WebhookUrlPolicyTest` cover signing, backoff, 410, ownership,
  the secret shown once, and private, IPv6, mapped and rebinding addresses.
- The end-to-end script checks that the stream pushed the mission's steps and photos.

## 6. HATEOAS — links as the set of allowed actions

**Problem.** Whether a command can be cancelled, approved or rejected depends on its status, the
user's role and the four-eyes rule. If every client re-implements these rules, they drift from the
server's.

**Decision.** Responses carry `_links` with **only the actions possible right now**:

- a command has `cancel` while it is still on Earth;
- a command waiting for approval has `approve` and `reject`;
- a mission has `cancel` while any step is on Earth;
- a photo has `image`: the same URL, with `type: image/png` as the media type to ask for.

Each link names its method:

```json
"_links": {
  "self":   { "href": "/api/v1/commands/42" },
  "rover":  { "href": "/api/v1/rovers/1" },
  "cancel": { "href": "/api/v1/commands/42/cancel", "method": "POST" }
}
```

The frontend draws an action button **only if the link is there** and follows it exactly
(`ApiClient.follow`). It never decides on its own that a command is cancellable. Roles only hide
buttons the server would refuse anyway, and the server checks them again on every call.

**Trade-off.** This is "level 3" of the Richardson maturity model in a pragmatic form. The links
describe *actions*, not a full hypermedia format such as HAL-FORMS or Siren; that keeps the JSON
simple while giving the main benefit, which is that the rules live in one place.

**Tests.**
- `CommandsIT` and `ApprovalsIT` check that the links appear and disappear with the status.
- `MissionsIT` checks `_links.cancel`.
- `PhotosIT` checks `_links.image.type`.

## 7. Contracts — command and telemetry dictionaries vs OpenAPI

**Problem.** Two systems that never share code at runtime must agree on every packet, and the
browser must agree with the API.

**Decision: two contracts, each for its audience.**

- **The link contract is code** (`redlink-contract`), like a real mission's command dictionary.
  - `RoverCommand` and `TelemetryPayload` are **sealed interfaces** with one record per entry, and
    the type travels inside the object (`{"type":"MOVE","distanceMeters":5}`). A packet can never
    say MOVE with ROTATE's parameters, and a new entry is a compile error in every `switch` that
    forgets it, on both sides of the link.
  - Bean Validation on the records (e.g. `@Min(1) @Max(20) distanceMeters`) runs on the API, the
    uplink and the downlink alike.
  - `RoverSpecs`, the rover's published spec sheet, is shared: the rover executes with these
    numbers and Earth plans missions with them, so the planner's forecast matched the rover's real
    end state exactly in the live test.
- **The public API contract is OpenAPI 3.1**, generated from the code by springdoc. A small
  customizer fixes the polymorphic `type` mapping. The frontend **generates its TypeScript types
  from it** (`npm run gen:api` → `schema.d.ts`), so a backend change that breaks the contract
  breaks the frontend build, not the user.
- **Content negotiation** is part of the contract
  ([ADR](../adr/photo-content-negotiation.md)). `GET /photos/{id}` is one resource with two
  representations:
  - JSON metadata, the default, revalidated with `no-cache`;
  - `image/png`, cached `immutable` for a year.

  Anything else is `406`. Both carry `Vary: Accept` and their own strong ETag.

**Alternatives.**
- Protobuf or gRPC would give a stronger link schema, but the course is about HTTP and JSON.
- A hand-written OpenAPI file drifts from the code; generating it in both directions keeps one
  source of truth per contract.

**Tests.**
- `CommandJsonTest` and `TelemetryJsonTest` check the exact wire format, round trips, and that
  every dictionary entry has exactly one record.
- `ApiDocsIT` checks the published document, including the polymorphic schemas.
- `PhotosIT` covers JSON, PNG, `*/*`, q-values and 406.

## 8. Security

**Problem.** Operators must be authenticated and authorised by role. Nobody but the two link ends
may inject packets. Outbound HTTP must not become an attack tool. And nothing may leak.

**Decision** ([ADR](../adr/jwt-hs256-and-link-hmac.md)):

- **Operators: JWT bearer tokens.**
  - Mission control issues them itself: `POST /auth/token` checks a bcrypt password and returns a
    15-minute HS256 token with `iss`, `aud`, `roles`, `jti`.
  - The same app validates them as an OAuth2 resource server, with the algorithm pinned and the
    issuer, audience and expiry checked.
  - It is stateless, so there is no CSRF exposure: tokens go in the header, never in cookies.
  - **Login throttling** counts failures per username *and* client IP (5 in 15 min →
    `429` + `Retry-After`), so an attacker cannot lock a real operator out.
- **Roles with method security.** `VIEWER < OPERATOR < FLIGHT_DIRECTOR`, checked with
  `@PreAuthorize` on every endpoint; only login, health and info are public. Drilling needs a
  second person (four-eyes, chapter 4).
- **Link: HMAC-SHA256 over the raw body.**
  - Header: `X-Link-Signature: t=<unix>,v1=<hex>`. `PacketSigner` lives in the contract, so both
    sides sign identically.
  - The signature covers the timestamp: requests older than 5 min are rejected (replay window), and
    replays inside the window die on packet-id dedupe.
  - Comparison is constant-time, and every failure gets the same `401`; the reason is only logged.
  - The link endpoints (`/flight/**`, `/dsn/**`) have their own filter chain that accepts only
    signed packets. A user JWT is useless there, and a link signature is useless on the API.
  - Oversized bodies are rejected before the signature is checked.
- **Webhooks:** signed per webhook, SSRF-checked (chapter 5). Users only ever see their own
  webhooks; others' are `404`, not `403`, so ids cannot be probed.
- **No leaks.**
  - Errors are Problem Details without stack traces, SQL or parser messages (chapter 9).
  - Only the `health` and `info` actuator endpoints are exposed.
  - IDs that matter (webhooks, photos) are random UUIDs.
  - No secret is in the repository: dev passwords exist only in `application-local.yml`. The
    Docker demo takes every secret from `.env`, and the `demo` profile refuses to start without a
    user password. A test run showed that an unresolved `${…}` would otherwise have been bound
    literally as a known password; that was fixed.
- **Deployment.** In Docker Compose only nginx is published. Both apps, the database and the link
  endpoints stay on the internal network. nginx adds `nosniff`, `X-Frame-Options`, a strict
  Content-Security-Policy and `Referrer-Policy`.

**Tests.**
- `MissionControlSecurityTest` covers login (an uncacheable token, the same answer for a wrong
  password and an unknown user), throttling per client, tampered, unsigned, expired, wrong-issuer
  and wrong-audience tokens, the role matrix, CORS preflight, security headers, and on the link
  chain unsigned and tampered packets, user tokens refused, and oversized packets rejected before
  verification.
- `RoverSecurityTest` and `PacketSignerTest` cover the signature, the tolerance and tampering.
- The webhook tests and `WebhookSsrfIT` cover outbound safety.
- The ITs check the role rules of their endpoints (e.g. `CommandsIT`: viewers cannot send or
  cancel commands; `ApprovalsIT`: approval needs a flight director).

## 9. Error design — RFC 9457 Problem Details

**Problem.** Clients must react to errors programmatically ("rover in safe mode → offer REBOOT") and
show humans something useful, without the server leaking internals.

**Decision.** Every error, including Spring's own (405, 406, 415, malformed JSON, type mismatches,
validation, access denied), is an `application/problem+json` document:

```json
{
  "type": "https://redlink.example/problems/mission-plan-infeasible",
  "title": "Mission plan infeasible",
  "status": 422,
  "detail": "Step 2 (MOVE) cannot be carried out by Earth's last knowledge of the rover",
  "instance": "/api/v1/rovers/1/missions",
  "step": 2,
  "errors": ["steps[1]: meter 1 would hit a rock at (12,40)"]
}
```

- **Stable `type` URIs** (`ProblemTypes`), 37 of them, are the machine-readable part. Clients
  switch on the type, never on the message.
- **Extension members** carry details: `errors` for validation (`field: message`, sorted), `step`
  for missions.
- **Headers belong to errors too:** `429` and `503` carry `Retry-After`.
- **Status codes are chosen by meaning:**

  | Status | Used for |
  |---|---|
  | `400` | malformed input |
  | `401` | not authenticated |
  | `403` | role or four-eyes |
  | `404` | not found, or not yours |
  | `406` | no acceptable representation |
  | `409` | conflicts with the current state (not cancellable, idempotency key in progress, rover busy) |
  | `412` / `428` | preconditions |
  | `422` | well-formed but impossible right now (safe mode, insufficient battery, infeasible plan, idempotency key reused) |
  | `429` | throttled |
  | `503` | link unavailable, too many streams |

- **One place.** Features throw subclasses of `ApiException` (status, type, title, extra properties,
  extra headers), and `ApiProblemHandler` renders all of them the same way. An unexpected exception
  becomes a generic `500 internal-error`, and the stack trace goes only to the log.

**Tests.** Every IT asserts the `type`, the status and, where relevant, the headers of its errors
(e.g. `RoversIT`: a 404 problem for an unknown rover, a 400 problem for a non-numeric id).

---

## Limits and future work

Stated plainly, because a design is defined as much by what it leaves out:

- **Single instance.**
  - SSE fan-out is in memory (`SseHub`). Scaling out would need PostgreSQL `LISTEN/NOTIFY` (or a
    broker) so that every instance hears every event; the `rover_events` table already makes resume
    work across instances.
  - The login throttle is in memory too; it would move to Redis.
  - The outboxes, the transmitter lock and all concurrency control already work with several
    instances.
- **Webhook secrets are stored in plain text**, because they are needed to sign. Hardening:
  encrypt them at rest with a key from a secret manager.
- **HS256 with a shared key** is right while the issuer and the verifier are one process. With an
  external identity provider: RS256 and a JWK Set URI (only `SecurityConfig` and `TokenService`
  change).
- **The Docker Compose stack was never started on the development machine**, because Docker Desktop
  hangs there. The images, the nginx configuration and the CSP were reviewed and the Compose file
  validated (`docker compose config`). The same end-to-end script passed against the apps directly
  and through a reverse proxy. Running `docker compose up --build` and the script against
  `http://localhost:8088` is the remaining check.
- **Earth plans nominally.** Dust storms and stuck wheels are random events on the rover; the
  mission forecast assumes neither.
- **Not built:**
  - the Earth → Mars intro flight, the NASA glTF model and post-processing in the 3D scene;
  - the real current Earth–Mars light time;
  - refresh tokens (the 15-minute login is deliberate).
