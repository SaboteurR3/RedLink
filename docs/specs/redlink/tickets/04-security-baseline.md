# 04 — Security baseline

Status: ready-for-human
Source spec: ../SPEC.md §8 (Auth), §14.8
Decision record: ../../../adr/jwt-hs256-and-link-hmac.md

## Scope

### mission-control: operators (users)
- Flyway `V1__users.sql`: `users` (username unique, lower-case, bcrypt hash, role, enabled).
- Roles `VIEWER < OPERATOR < FLIGHT_DIRECTOR` as a role hierarchy: a flight director can do
  everything an operator can.
- `POST /api/v1/auth/token` `{username, password}` → `200 {access_token, token_type, expires_in}`
  with `Cache-Control: no-store` (RFC 6749 §5.1). JWT HS256, 15 min, claims `iss`, `aud`, `sub`,
  `roles`, `iat`, `exp`, `jti`; issuer and audience validated on every request.
- `GET /api/v1/auth/me`: who am I, which roles (used by the frontend).
- Login throttling: 5 failed attempts per (username, client IP) in 15 min → `429` + `Retry-After`.
  Same `401` for unknown user and wrong password (no user enumeration; Spring also equalises timing).
- Dev users (`viewer`, `operator`, `director`) seeded only under the `local` profile.

### mission-control: HTTP security
- Stateless, no sessions, CSRF off (bearer tokens are not sent automatically by browsers, so
  CSRF does not apply), no form login / HTTP basic.
- Public: `POST /api/v1/auth/token`, `/actuator/health/**`, `/actuator/info`, `/error`.
  Everything else requires a token. Method security (`@PreAuthorize`) for roles.
- `401` with `WWW-Authenticate: Bearer` (RFC 6750) and `403`, both as Problem Details.
- CORS: only configured origins (local: the Vite dev server), explicit methods and headers,
  exposes `Location`, `ETag`, `Retry-After`.
- Headers: `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control: no-store`,
  `Referrer-Policy: no-referrer`, a deny-all `Content-Security-Policy` (JSON API only; relaxed for
  Swagger UI in ticket 08).

### Both apps: deep-space link authentication
- `LinkSignatureFilter` on `/dsn/**` (mission-control) and `/flight/**` (rover-sim): reads the raw
  body (size-limited), verifies it with `PacketSigner`, then authenticates the request as the
  link (`ROLE_LINK`). Every failure → the same `401` Problem Details; the reason is only logged.
- rover-sim: everything except `/flight/**` and health/info is denied. No users at all.

### Secrets
- `REDLINK_JWT_SECRET`, `REDLINK_LINK_SECRET` (base64, ≥ 32 bytes) from the environment, no
  defaults; dev values only in `application-local.yml`. Validated at startup.

## Out of scope

Real `/dsn` and `/flight` endpoints (tickets 05, 11): tested here with test-only controllers.
Global Problem Details advice for all errors and Swagger UI (ticket 08). Refresh tokens.

## Acceptance criteria

- No token → 401 + `WWW-Authenticate`; expired, tampered, wrong-issuer and wrong-audience tokens → 401.
- Wrong role → 403; role hierarchy works (director passes operator checks).
- Login: valid → token usable on `/auth/me`; invalid → 401; 6th failed attempt → 429 + `Retry-After`;
  invalid body → 400.
- Link: valid signature → passes; missing, tampered, expired → 401.
- CORS preflight from an allowed origin passes; from another origin is rejected.
- Both apps start against PostgreSQL with Flyway; rover-sim exposes nothing else.

## Checklist

- [x] Dependencies (security, resource server, webmvc-test, security-test)
- [x] Configuration properties (JWT, link, CORS, login throttling) + local values
- [x] `users` migration, entity, repository, `UserDetailsService`, local seeder
- [x] Token issuing + `/auth/token`, `/auth/me`
- [x] Login throttling
- [x] Security filter chains, Problem Details entry point / access denied handler, CORS, headers
- [x] Link signature filter (both apps)
- [x] rover-sim security
- [x] Tests (MockMvc slices, no Docker needed)
- [x] ADR
- [x] Manual check against local PostgreSQL: Flyway V1 applied, users seeded, login, `/auth/me`, 401, 429, CORS, link 401, rover 404; missing/short secrets stop startup
- [x] `./mvnw verify` green without the two Docker-based context tests (Docker hangs here): 102 tests
- [x] Context-load tests (Flyway + JPA validation) run since ticket 05 with `-Plocal-db`

## Notes for later tickets

- Ticket 08: Swagger UI needs the deny-all CSP relaxed on its paths; validation errors (400) still
  use Spring's default problem (no `type`) until the global advice exists.
- Ticket 08: Jackson 3 sorts JSON properties alphabetically by default (problem bodies from MVC show
  `detail` first); decide whether to keep that.
- Spring Security 7 serves RFC 9728 metadata at `/.well-known/oauth-protected-resource` and points
  to it from `WWW-Authenticate`; it is public and says tokens are not certificate-bound.
- Tests: Boot's test scanning ignores `@RestController`s nested in a test class that has `@Test`
  methods; import probe controllers explicitly.
