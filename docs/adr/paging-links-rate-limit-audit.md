# Paging Link headers, per-user rate limit, audit log in the change's transaction

Status: accepted (ticket 31)

## Context

Lists were paged only in the body (`page.number`, `page.totalPages`), so clients had to build the
next URL themselves. Nothing stopped a script from sending commands as fast as it could. And
there was no answer to "who declared that conjunction?" except the logs.

## Decision

- **RFC 8288 `Link` headers** come from one `ResponseBodyAdvice` for every `PagedModel` response,
  so no controller can forget them.
  - The targets are the request's own path and query with only `page` replaced, so filters and
    `size` carry over.
  - They are relative, like the `_links` in the bodies.
  - `first` and `last` are always present; `prev` and `next` only when they exist.
  - CORS exposes `Link`. The frontend's audit view pages only by following them.
- **Rate limit** uses a token bucket per user on command and mission submission (one shared
  budget): a burst of 30, refilled evenly at 30 a minute.
  - Beyond it, the answer is `429 Too Many Requests` (RFC 6585) with `Retry-After` in whole
    seconds, rounded up, and the problem type `rate-limited`.
  - It is checked after authentication and authorisation, so it is keyed on the real user.
  - It is checked before the idempotency lookup, so replays count too: a client looping on
    replays is still a client looping.
  - Not used: the IETF `RateLimit` / `RateLimit-Policy` headers, because the draft's format has
    changed between versions. `Retry-After` is standard and enough to back off.
  - The bucket is in memory, so each instance limits on its own (see ticket 34).
- **The audit log** is an append-only table.
  - Services record into it inside the transaction of the change (`Propagation.MANDATORY`
    enforces this). An entry exists exactly when the change was committed: a refused or
    rolled-back request leaves nothing, and an idempotent replay does not run the change again,
    so it records nothing again.
  - Only real state changes are logged. Cancelling an already cancelled command is a no-op and
    leaves no entry.
  - Webhook URLs are logged by host only, because their path or query may carry the receiver's
    token.
  - Reading it is FLIGHT_DIRECTOR only.

## Consequences

- Log writes add one insert to each changing transaction, which is cheap.
- No retention job yet. The table only grows, slowly, with human actions; rover reports are not
  logged.
- Failed logins are not in the audit log. They are throttled and logged in the application log;
  a security audit of logins would need its own transaction, because a failed login throws.
