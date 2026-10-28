# 16 — Photos (Java2D, content negotiation)

Status: ready-for-human

## Scope

`TAKE_PHOTO` produces a real image. The rover renders what its camera sees from the terrain with
Java2D and downlinks it as `PHOTO` telemetry. Mission control stores it and serves it with HTTP
content negotiation: the same resource is JSON metadata or the PNG itself.

- **Contract:** `TelemetryType.PHOTO` and `TelemetryPayload.Photo(commandId, camera, x, y, heading,
  imageBase64)`. The image is a base64 PNG, at most 2 MB encoded, and is checked to start with
  the PNG signature.
- **rover-sim:** `PhotoRenderer` is pure, so the same input gives the same bytes. It draws a
  first-person voxel-space view along the heading from a 1.8 m mast:
  - NAVCAM: 90° field of view, grayscale, 320×240;
  - MASTCAM: 40° field of view, colour, 320×240.

  Sand, ground and rock have their own colours, rocks stand up as boulders, the crater rim lies
  beyond the map edge, and there is a Mars sky. The executor emits `PHOTO` right after the
  TAKE_PHOTO step succeeds, in the same transaction, before `COMPLETED`.
- **mission-control:**
  - V9 adds `PHOTO` to `telemetry_type_ck` and creates `photos`:
    - id = the telemetry packet id;
    - rover FK; command id without an FK, like telemetry;
    - camera, x, y, heading;
    - `image bytea`, `size_bytes`;
    - `taken_at` (Mars time), `received_at`.
  - A PHOTO packet is stored in the same transaction as its telemetry row, and dedupe comes for
    free through the packet id. The telemetry history keeps the metadata and drops the image (no
    megabytes in jsonb).
  - The SSE event `photo` carries the metadata.
- **API:**
  - `GET /api/v1/rovers/{id}/photos?page=&size=` (VIEWER): metadata, newest first, paged. 404
    for an unknown rover.
  - `GET /api/v1/photos/{id}` (VIEWER) with content negotiation:
    - `Accept: application/json`, `*/*` or none → metadata with `_links` (`self`, `rover`,
      `image`);
    - `Accept: image/png` → the bytes, `Content-Disposition: inline`;
    - anything else → `406`.

    Both answers carry `Vary: Accept` and their own strong ETag (from the PNG's SHA-256), and
    If-None-Match gives `304`. The image never changes: `private, max-age=31536000, immutable`.
    The JSON may gain fields in a release: `no-cache, private` (see ADR `photo-content-negotiation`). An unknown id gives `404 photo-not-found`.
- **Frontend:** a photo strip for the selected rover. It loads the PNGs with the bearer token
  (`Accept: image/png` → blob URL) and refreshes on the SSE `photo` event.

## Acceptance criteria

- [x] TAKE_PHOTO on rover-sim queues a PHOTO packet whose image decodes as a PNG of the camera's size.
- [x] Rendering is deterministic; NAVCAM is grayscale, MASTCAM is not.
- [x] A signed PHOTO downlink creates one photo row (duplicate delivery doesn't create a second).
- [x] The telemetry history shows the PHOTO metadata without `imageBase64`.
- [x] `GET /photos/{id}`: JSON by default and for `application/json`; PNG bytes for `image/png`;
      406 for `text/plain`; `Vary: Accept`, `ETag`, immutable `Cache-Control`; 304 on If-None-Match.
- [x] 401 without a token; 404 problem for an unknown photo or rover.
- [x] Rover photo list is paged, newest first.
- [x] Frontend shows the photos; the build and its tests pass.
- [x] `./mvnw verify -Plocal-db` green.

## Checklist

- [x] Contract: `PHOTO` type + `Photo` payload (+ test)
- [x] rover-sim: `PhotoRenderer` (+ unit test), emit from `CommandStepper` (+ IT)
- [x] mission-control: V9 migration, `Photo` entity/repository, store in `TelemetryService`
- [x] mission-control: `PhotoController` with content negotiation, problem type, SSE event
- [x] `PhotosIT` covering status codes and headers
- [x] Frontend photo strip
- [x] SPEC.md, README index
- [x] Full build green

## Notes for later tickets

- 18 (3D scene): `photo` SSE events carry x, y, heading; a camera marker or "photo taken here" pin is easy.
- 20 (compose): the renderer needs no fonts (no text drawn) and runs headless, so a slim JRE image works.
- Lombok was introduced in this ticket (user request): see AGENTS.md "Lombok"; all existing code converted.

## Verification

- `./mvnw verify -Plocal-db`: 184 unit tests (contract 75, mission-control 78, rover-sim 31) and
  142 integration tests (mission-control 120, rover-sim 22), all green. Frontend: 24 tests, build OK.
- Live run with both apps: a queued TAKE_PHOTO crossed the link and came back as a 320×240 PNG (16 KB);
  `Accept: image/png` → `Vary: Accept`, strong ETag, `max-age=31536000, private, immutable`;
  `Accept: text/html` → 406.
