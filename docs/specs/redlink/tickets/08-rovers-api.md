# 08 — mission-control: rovers API, ETag/If-Match, Problem Details

Status: ready-for-human
Source spec: ../SPEC.md §7, §8 (Rovers), §9

## Scope

### Rovers (Earth's view: "last known state", never the rover's truth)
- Flyway `V2__create_rovers.sql`: `rovers` (name, site, last known position/heading/battery/
  temperature/storage/mode, `last_telemetry_at`, `version`). Rover 1 seeded at its landing cell;
  `last_telemetry_at` stays null until telemetry arrives (ticket 11).
- `GET /api/v1/rovers` (VIEWER): paged (`page`, `size`, max 100), stable JSON page shape.
- `GET /api/v1/rovers/{id}` (VIEWER): `ETag: "<version>"`, `Cache-Control: private, no-cache`;
  `If-None-Match` matching → `304 Not Modified` with no body.
- `PATCH /api/v1/rovers/{id}` (OPERATOR): **JSON Merge Patch** (RFC 7396,
  `application/merge-patch+json`; anything else → 415). Only `name` is writable; unknown fields,
  `null` or blank → 400.
  - `If-Match` required: missing → `428 Precondition Required` (RFC 6585), stale → `412
    Precondition Failed`, `*` or current → `200` with the new `ETag`.
  - Lost-update safe under concurrency: the If-Match check and the update share one transaction
    and `@Version` catches a writer that slipped in between → 412.

### Sites (terrain for the frontend)
- `GET /api/v1/sites/{siteId}` (VIEWER): metadata (grid, elevation range, start cell).
- `GET /api/v1/sites/{siteId}/heightmap` and `/surface`: `image/png`, content-hash `ETag`,
  `Cache-Control: private, max-age=86400`, `If-None-Match` → 304. Unknown or malformed site id → 404.

### Errors
- One global `@RestControllerAdvice` (extends `ResponseEntityExceptionHandler`): every error is
  RFC 9457 with a stable `type` — validation (with `errors`), malformed body, not found, 412, 428,
  403 from method security, catch-all 500 without internals. Login's own handler keeps priority.

### API documentation
- springdoc-openapi 3.1.1: `/v3/api-docs` and Swagger UI `/swagger-ui.html`, public, bearer-JWT
  security scheme. Swagger paths get their own filter chain with a CSP that lets the UI load;
  the API keeps the deny-all CSP. Can be switched off with `springdoc.api-docs.enabled=false`.

## Out of scope

Commands and HATEOAS links (09), updating last-known state from telemetry (11).

## Acceptance criteria

- 200/304 with exact `ETag` and `Cache-Control`; 404 problem for unknown rover/site.
- PATCH: 428 without If-Match, 412 stale, 200 with new ETag, 415 wrong media type, 400 for
  unknown field / null / blank name, 403 for VIEWER.
- Two concurrent PATCHes with the same If-Match: exactly one 200, one 412.
- Heightmap PNG with ETag; 304 on revalidation.
- OpenAPI document lists the endpoints and the bearer scheme; Swagger UI loads.

## Checklist

- [x] Ticket detailed
- [x] springdoc dependency, contract `TerrainFiles` public
- [x] Migration, `Rover` entity, repository
- [x] `EntityTags` (If-Match / If-None-Match parsing)
- [x] `RoverService` + `RoverController` (+ DTOs)
- [x] `SiteController`
- [x] `ApiProblemHandler`, problem types, login handler precedence
- [x] OpenAPI config + docs security chain
- [x] Integration tests (`RoversIT`, `SitesIT`, `ApiDocsIT`)
- [x] SPEC §8 updated
- [x] `./mvnw verify -Plocal-db` green: 131 unit + 49 integration tests
- [x] Live check: list, ETag 304, 404/400/428 problems, Swagger UI and /v3/api-docs

## Notes for later tickets

- Ticket 09: reuse `ApiExceptions`, `ApiProblemHandler`, `EntityTags`, `PagedModel` + explicit
  `page`/`size` params (no client-chosen sort), and the `TestTokens` helper in ITs.
- Ticket 11: telemetry updates must change `rovers` through the entity (so `version` and the ETag
  change) and set `last_telemetry_at` to the packet's `roverTime`.
- Decided: Jackson's alphabetical property sorting is off (`spring.jackson.mapper.sort-properties-alphabetically: false`),
  so problems read type, title, status, detail, instance.
- Spring turns a 200 `ResponseEntity` with an `ETag` into 304 by itself for GET/HEAD (no
  `checkNotModified` call needed); for writes `If-Match` is checked inside the transaction.
