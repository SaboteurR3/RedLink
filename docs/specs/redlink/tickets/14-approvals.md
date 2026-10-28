# 14 — Approval workflow

Status: ready-for-human
Source spec: ../SPEC.md §5, §8 (Commands)

## Scope

Risky commands (`DRILL_SAMPLE`) need a flight director's approval before they may leave Earth.

### API (FLIGHT_DIRECTOR)
- `POST /api/v1/commands/{id}/approve` → `200`, command `PENDING_APPROVAL → QUEUED`.
- `POST /api/v1/commands/{id}/reject` with optional `{"reason": "..."}` (≤ 500 chars) → `200`,
  command `REJECTED` (final).
- **Four-eyes rule**: nobody approves a command they created → `403 four-eyes-violation`
  (the role allows approving, this specific case does not). Rejecting your own is allowed (it only
  stops something).
- Safe to retry: approving an approved command / rejecting a rejected one → `200` again.
  Contradicting an earlier decision (reject after approve, approve after cancel/reject) → `409
  command-not-pending-approval`.
- Approval is checked against Earth's last knowledge like a new command: safe mode → 422,
  drilling below 30 % battery → 422 (now also checked when the drill is first submitted).
- After approval the command waits the normal hold (5 s) **from the approval**, not from its
  creation, so the cancel window is never skipped (new `queued_at`).

### Representation
- `CommandResponse.approval`: `{state: PENDING|APPROVED|REJECTED, decidedBy, decidedAt, reason}`,
  `null` for commands that need no approval.
- `_links.approve` / `_links.reject` while `PENDING_APPROVAL`. Links describe what the command's
  state allows (the same JSON is broadcast to every viewer over SSE); role and four-eyes are
  checked on every call, and the UI shows the buttons only to directors who did not create it.
- Each decision is published as a `command` event.

### Frontend
- Approve / Reject (with reason) buttons from the links, for directors on other people's commands;
  approval state shown in the timeline.

### Data
- Flyway `V7`: `commands.queued_at`, `decided_by`, `decided_at`, `decision_reason`; transmitter
  uses `queued_at`.

## Acceptance criteria

- Operator approve → 403; director approves own → 403 four-eyes; another director → 200 QUEUED,
  links change (cancel yes, approve no), event published.
- Approved drill is transmitted 5 s after approval, not earlier.
- Reject with reason → REJECTED final; too long reason → 400.
- Retry approve → 200; reject after approve → 409; approve after cancel → 409.
- Low battery → 422 at submit and at approval.

## Checklist

- [x] Ticket detailed
- [x] V7 migration, entity rules, transmitter on `queued_at`
- [x] Service + endpoints + problem types
- [x] Representation (`approval`, links)
- [x] Integration tests
- [x] Frontend buttons + regenerated types
- [x] SPEC updated
- [x] `./mvnw verify -Plocal-db` (145 unit + 113 integration), `npm run build`, `npm test` (23) green
- [x] Live check with dev users: operator approve 403, director approves operator's drill → QUEUED, director approving own → 403 four-eyes, reject with reason → REJECTED

## Notes for later tickets

- Local dev has one director; the four-eyes rule means a director's own drill needs a second
  director account (create one in `LocalUserSeeder` if the demo needs it).
- Dev database: drill #5 is QUEUED (approved during the live check); it executes next time both
  apps run.
- Ticket 15: `command.completed` / `command.failed` webhooks; approval decisions could become
  `command.approved` / `command.rejected` events too.
