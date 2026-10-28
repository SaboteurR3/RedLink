# 24 — UX quick wins

Status: ready-for-human

## Scope

Fix the friction found while testing by hand: refusals that don't say what to do, approvals that
are easy to miss, results you have to hunt for, and orders that are pointless or certain to fail.

- **Blocking reasons.** `409 rover-busy` gets a problem property `blockingCommands`: the
  unfinished commands `{id, type, status, cancellable}`, oldest first, at most 20. The console
  lists them under the error, with **Cancel** buttons for the ones still on Earth.
- **Director inbox.**
  - Mission control loads the pending approvals separately
    (`GET /rovers/{id}/commands?status=PENDING_APPROVAL`), so older ones are not hidden behind the
    50 newest commands.
  - Flight directors see a badge ("⚑ 2 to approve") in the top bar. It opens a list of other
    people's pending drills with Approve / Reject, following the HATEOAS links.
  - A director's own pending drills are listed as "waiting for another director".
- **Toasts** (bottom right, auto-dismiss after 6 s, at most 4):
  - a command completed / failed / was refused (green / red);
  - a rover event of severity WARNING or CRITICAL;
  - a solar conjunction began or ended;
  - a new photo arrived.
- **Published spec sheet.** `GET /api/v1/specs` (VIEWER) returns `RoverSpecs` as JSON: costs,
  limits and step durations. It is the same numbers the rover executes with and the planner
  plans with, served with `Cache-Control: max-age=3600` and an ETag. The step durations move from
  `FlightRules` into `RoverSpecs`. `GET /link` also gives `queueHoldSeconds`.
- **Smarter console:**
  - an estimate under the form: time on Mars, battery cost (a range for MOVE: ground to sand),
    storage, and "about N s until you see the result" (hold + light time both ways + execution);
  - warnings: "already facing EAST: nothing would happen", "not enough battery for this drive by
    the last report", "drilling needs 30 % battery";
  - in safe mode, only REBOOT can be chosen, with a banner explaining why.

  The estimate logic is a pure, unit-tested module.

## Acceptance criteria

- [x] Busy problem lists the blocking commands; the UI cancels them from the error.
- [x] Pending approvals beyond the newest 50 show up; the badge count is right; Approve/Reject work from the inbox.
- [x] Toasts appear for the listed events and go away.
- [x] `GET /specs`: values equal `RoverSpecs`, cache headers, 304; rover-sim uses the moved durations.
- [x] Console estimate and warnings; safe-mode lock.
- [x] Tests: `MissionsIT` (blocking commands), a specs IT, frontend unit tests for the estimate.
- [x] `./mvnw verify -Plocal-db`, `npm test`, `npm run build` green; checked in the browser.

## Checklist

- [x] Backend: busy details, `/specs`, durations in `RoverSpecs`, `queueHoldSeconds`
- [x] Frontend: busy UI, pending approvals load, inbox, toasts, estimate module + console
- [x] Tests
- [x] Browser check
- [x] SPEC, GUIDE, index

## Notes for later tickets

- 25 (path preview): the console estimate gives a ground-to-sand range for MOVE; the preview will give the exact cost along the real path.
- Safe-mode lock not exercised in the browser (the rover stayed NOMINAL); covered by the estimate unit tests and the console logic.

## Verification

- `./mvnw verify -Plocal-db`: 203 unit + 176 integration tests green (`SpecsIT` new; `MissionsIT`, `LinkIT` extended).
- Frontend: 40 tests (7 new for the estimate), type check, build.
- Browser check (headless Edge, director + operator at once):
  - the badge rose live when the operator sent a drill, and Approve from the inbox brought it back down;
  - "already faces SOUTH" warning; estimate "3 s on Mars · −3 to −6 % · result on screen in about 28 s";
  - a mission while a photo was queued showed the blocking list, and Cancel removed it;
  - a toast appeared ("#54 PHOTO NAVCAM cancelled").
- Side effect on the dev database: the check approved and ran one drill (#53).
