# 21 — Write-up and demo script

Status: ready-for-human

## Scope

The written part of the course project and a rehearsable demo.

- `docs/writeup/WRITEUP.md`: the architecture in one page, then the nine chapters of SPEC §14.
  Each chapter covers the problem, the decision, where it lives in the code, the HTTP evidence
  (status codes, headers), the alternatives and trade-offs, and the tests that prove it. It ends
  with an honest section on limits and future work (single-instance SSE, plaintext webhook
  secrets, Docker run not verified on the dev machine, …).
- `docs/writeup/DEMO.md`: a 15-minute demo script. Setup, then numbered scenes with the exact
  clicks and `curl` commands, what to point out, and a fallback for each risky step.
- `README.md` links both.

No code changes.

## Acceptance criteria

- [x] All nine chapters, each with code pointers and at least one test reference.
- [x] Every claim matches the code as it is (endpoints, statuses, headers, config values).
- [x] Demo script runnable with the local apps (no Docker needed), with the Compose variant noted.
- [x] Limits and future work stated plainly.
- [x] README links; ticket index updated.

## Checklist

- [x] WRITEUP.md
- [x] DEMO.md
- [x] README links, index

## Notes for later tickets

- All 21 tickets are done. Open item from ticket 20: run `docker compose up --build` and the
  end-to-end script on a machine where Docker works; then remove that point from the write-up's
  "Limits".

## Verification

- Every test named in the write-up was checked against the actual test methods. Claims that did
  not hold were corrected: there are 34 problem types, not "about 40"; the SSE 503 and the published
  problem schema have no tests (now stated); the idempotency and security test lists were
  rewritten from the real names.
- The demo's `curl` scenes were dry-run against both apps: 202 + `Location` + `Retry-After`;
  replay + `Idempotent-Replayed` and 422 for another body; 409 not cancellable; 403 four-eyes;
  JSON / PNG / 406 with `Vary` and cache headers; 304, 428, 412; Swagger UI redirect.
  - Fixed on the way: scene 2 sends a ROTATE instead of a MOVE (a MOVE could hit a rock and put
    the rover in safe mode before the rest of the demo); scene 8 now reads the real ETag; the
    stale tag is `"0"`.
- The UI scenes (1, 2, 4–6, 9) follow features checked in tickets 13–19; the full UI walk-through
  was not rehearsed in a browser.
