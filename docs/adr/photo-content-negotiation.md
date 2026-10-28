# Photos: one resource, two representations

Status: accepted (ticket 16)

## Context

A photo has metadata (camera, position, Mars time) and an image. The usual shortcut is two URLs,
`/photos/{id}` and `/photos/{id}/image`. The course is about HTTP semantics, and RFC 9110 already
has the mechanism for "the same thing in different formats": proactive content negotiation.

## Decision

- `GET /api/v1/photos/{id}` serves `application/json` (the default for `*/*` or no `Accept`) or
  `image/png`. Two `@GetMapping`s with `produces` let Spring pick by `Accept`, including
  q-values. Anything else gets `406`.
- Both responses carry `Vary: Accept`, so shared caches keep the representations apart.
- Each representation has its own strong ETag, derived from the PNG's SHA-256 (`"<hash>"` for
  the image, `"<hash>-json"` for the metadata), and If-None-Match gives 304.
- The image can never change (same id = same bytes), so it is `private, max-age=1y, immutable`.
  The JSON can gain fields in a later release, so it is `no-cache` and revalidated by ETag.
- `_links.image` names the same URL plus `type: image/png`, so clients learn the content-type
  switch from the response instead of hard-coding it.
- The photo id is the PHOTO packet id: random (not guessable), and dedupe comes for free with the
  telemetry insert (`ON CONFLICT DO NOTHING`).

## Consequences

- An `<img src>` cannot send `Authorization` or choose `Accept`. The frontend therefore fetches
  the image with the bearer token and `Accept: image/png` and shows it as a blob URL.
- The telemetry history stores the PHOTO payload without `imageBase64`, so the image exists only
  once, in `photos.image`.
