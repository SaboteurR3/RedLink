# 31 — REST extras: Link paging headers, rate limiting, audit log

Status: ready-for-human

## Scope

- **RFC 8288 `Link` headers** (`first`, `prev`, `next`, `last`) on every paged list:
  - added by one `ResponseBodyAdvice` for `PagedModel` (`web/PageLinks`);
  - relative targets that keep the request's own query and change only `page`;
  - exposed through CORS.
- **Rate limiting** of command and mission submission per user:
  - a token bucket with a burst of 30, refilled at 30 a minute (`redlink.rate-limit.submissions`);
  - beyond it, `429 rate-limited` with `Retry-After`;
  - in memory, per instance.
- **Audit log:**
  - `audit_log` table (V12) and `audit/AuditLog`;
  - recorded inside the transaction of each change: command submitted, cancelled, approved or
    rejected; mission submitted or cancelled; conjunction declared or ended; webhook registered or
    deleted;
  - `GET /api/v1/audit` (FLIGHT_DIRECTOR), with filters `actor`, `action` and `[from, to)`, newest
    first, paged.
- **Frontend:** an **Audit** dialog for flight directors that pages only by following the `Link`
  header (`api/links.ts`, a tested parser).
- ADR `docs/adr/paging-links-rate-limit-audit.md`; SPEC and GUIDE updated.

## Acceptance criteria

- [x] Paged lists carry correct `Link` headers (first/prev/next/last, filters kept); tested exactly.
- [x] The 31st quick submission is `429` with `Retry-After` and a problem; other users unaffected;
  missions share the budget.
- [x] Changes are in the audit log with who, what and when; refused requests and idempotent replays
  add nothing; only flight directors read it (403 / 401 otherwise).
- [x] `./mvnw verify -Plocal-db` green; frontend tests and build green.

## Notes for later tickets

- Ticket 34: the rate limiter's buckets (like the login throttle) are per instance.
- Failed logins are not audited (see the ADR).
- Found and fixed: running `tsc -b` emitted `.js` files next to the sources and Vitest ran the tests
  twice. They were deleted; type checks use `tsc --noEmit` (as `npm run build` does).

## Verification

- New tests:
  - `SubmissionRateLimiterTest` (3);
  - `RateLimitIT` (exact 429, `Retry-After`, problem type; missions share the budget; another user
    is unaffected);
  - `AuditIT` (4: recording and order, filters, rollback and replay, exact `Link` headers on the
    audit and command lists, 403/401/400);
  - frontend `links.test.ts` (2).
- Full `./mvnw verify -Plocal-db`: unit 75 + 101 + 36, integration 158 + 26, all green.
- Frontend: 56 tests, type check, build green.
