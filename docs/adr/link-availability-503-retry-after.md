# Link availability: hold on Earth, 503 + Retry-After towards Mars

Status: accepted (ticket 19)

## Context

During a solar conjunction (and outside relay windows) the link carries nothing. Commands are
created on Earth while the link is down, and telemetry keeps arriving at the rover's outbox. The
two systems share no state: the rover cannot ask mission control whether a conjunction is on.

## Decision

- **Uplink:** commands are accepted as usual (202) and stay QUEUED; the transmitter simply does
  not take them while the window is closed or a conjunction is active. The outbox already gives
  the right semantics: nothing is lost, and everything is still cancellable.
- **Downlink:** mission control answers `/dsn/downlink` with **503 Service Unavailable** and
  `Retry-After` (a problem `link-unavailable`). That is exactly HTTP's "temporarily unable, try
  again after this long" (RFC 9110 §15.6.4, §10.2.3). The rover's outbox treats 503 as retryable
  anyway; it now also honours `Retry-After` (seconds or HTTP-date, capped at 1 h against hostile
  values) and does not try the rest of that batch. So it stops hammering Earth without knowing
  anything about conjunctions.
- The 503 comes after the HMAC check: an unsigned request still gets 401, so the endpoint does
  not reveal link state to strangers.
- **Conjunction resource:** `PUT /link/conjunction {active}` is idempotent (the same value is 200
  with no version bump). `If-Match` is optional: the PUT sets an absolute value, so a blind repeat
  is harmless, while a client that read the state can still detect a concurrent change (412).
- **Windows** are a pure function of the clock (cycles from the Unix epoch), so no state is needed
  and all instances agree.

## Consequences

- The rover only learns of a conjunction through 503s, which is realistic: it would see no
  contact either.
- `GET /link` depends on the clock (windows), so it has no ETag; the conjunction sub-resource,
  which only changes on a PUT, has one.
