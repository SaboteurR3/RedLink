# 22 — QA pass (end to end, as a tester)

Status: ready-for-human

## Scope

Test the whole running system the way a QA engineer would: every feature, the happy path and the
edges (bad input, wrong role, wrong state, repeats, races, odd headers), through the API and the
UI. Every bug found gets a fix and a regression test. Then write a business-level guide to what
RedLink does (`docs/GUIDE.md`) for the product owner to read before testing it themselves.

## Test areas

- [x] Auth: login, wrong password, unknown user, throttling, token claims, `/auth/me`, malformed bodies
- [x] Rovers: list, paging limits, get, 304, PATCH (merge patch rules, 415, 428/412), unknown/invalid ids
- [x] Sites: site, heightmap/surface caching, unknown/malicious ids
- [x] Commands: every type's bounds, unknown type, safe mode / battery 422, list filters, cancel, approve/reject, reasons
- [x] Idempotency: key format, per user, replay, reuse
- [x] Missions: validation, infeasible, busy, cancel, list
- [x] Photos: list, JSON/PNG/406, 304
- [x] Webhooks: create rules, SSRF, limit, list, delete, deliveries, ping
- [x] Events: tickets, stream, resume
- [x] Telemetry history: filters, ranges, bad dates
- [x] Link: status, conjunction
- [x] Generic HTTP: 404 paths, 405, 415, 406, oversized/malformed bodies, problem shape everywhere
- [x] Rover behaviour: drive into rock → safe mode → refusals → reboot; battery and storage limits
- [x] UI: login/logout, roles, console, timeline actions, missions, photos, 3D/2D, link panel, errors

## Bugs found

| # | Symptom | Cause | Fix | Regression test |
|---|---|---|---|---|
| 1 | 405, 406, 415 problems had no `type` | Spring creates those problems lazily (`body == null`), so the handler never saw them | `ApiProblemHandler.handleExceptionInternal` takes the `ErrorResponse` body and sets `ProblemTypes.forStatus(…)` (new types `method-not-allowed`, `not-acceptable`, `unsupported-media-type`) | `ApiErrorsIT.builtInErrorsCarryAType` |
| 2 | **`MOVE 1.5` drove 1 m; `"3"` and `"heading": 1` (→ EAST) were accepted** | Jackson coerces floats, strings and enum ordinals by default | `StrictJsonConfig`: integers refuse floats/strings, floats and booleans refuse strings, enums refuse numbers | `ApiErrorsIT.wrongValuesAreRefused…` (6 cases) |
| 3 | Wrong enum / unknown command type gave only "not valid JSON of the expected shape" | the handler ignored the cause | `ReadableErrors`: field path + allowed values, e.g. `steps[1].heading: must be one of NORTH, EAST, SOUTH, WEST`, `type: 'FLY' is not one of MOVE, …`; the client's value is echoed only if it is a plain short word | `ApiErrorsIT` (dictionary, missions by position, no echo of markup, plain bad JSON) |
| 4 | `GET /api/v1/auth/token` answered 401 | the security rule permitted only POST | permit the path for every method → 405 `Allow: POST` | `ApiErrorsIT.wrongMethodOnTheLoginEndpointIs405…` |
| 5 | Invalid stored token: the user landed on the login page without the "session expired" message; logout left the token in the API client's memory | a second `logout()` without reason overwrote the message | `App.tsx`: a 401 logs out once with the message; `signOut` clears the in-memory token | UI walk-through (headless Edge) |
| 6 | three.js console warning `PCFSoftShadowMap has been removed` | R3F's default soft shadows | `<Canvas shadows="percentage">` | UI walk-through (no warning) |

## Checklist

- [x] API probes
- [x] Rover behaviour scenarios
- [x] UI walk-through (headless browser)
- [x] Fixes + regression tests
- [x] Full build green
- [x] `docs/GUIDE.md`

## Known limitations (found, deliberately not changed)

- A 406 still carries the resource's `ETag` header (the controller set it before content negotiation failed). Harmless: no body is cached for a 406.
- Requests Tomcat rejects before Spring (e.g. an encoded slash `%2F` in a path) get Tomcat's plain HTML 400 page, not a problem document. No version or internals are shown.
- Unknown JSON fields are ignored (tolerant reader); a misspelt required field still fails validation.
- `THREE.Clock` deprecation warning comes from inside React Three Fiber 9.8.1.
- The `local` profile shows health details and allows http/private webhook URLs on purpose (development); the base configuration is strict.

## Verification

- API probes (about 120 requests): auth, generic HTTP, rovers, sites, every command type's bounds, idempotency keys, approvals, missions, telemetry history, webhooks (incl. SSRF rules), event tickets and stream, link. Everything else behaved as specified.
- Live rover scenario: a 20 m drive west stopped at a rock after 3 m (FAILED OBSTACLE 15 %), safe mode, a photo refused by Earth with 422, REBOOT, back to NOMINAL.
- UI walk-through in headless Edge with three users at once: 24 checks, all pass after the fixes (login errors, roles, send, cancel, approval across users live, mission refusal message, conjunction banner pushed to others, 2D toggle and reload, invalid token, logout).
- `./mvnw verify -Plocal-db`: 203 unit + 175 integration tests green (12 new in `ApiErrorsIT`). Frontend: 33 tests, build OK.
- Test data created during QA was removed (rover name restored, QA webhooks deleted).
