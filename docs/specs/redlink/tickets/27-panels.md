# 27 — Missions panel, photo viewer, webhooks page, first-visit hints

Status: ready-for-human

## Scope

Frontend only: the APIs existed, but had no screens.

- **Missions panel** (under the console):
  - the 8 newest missions, each with name, status (derived by the server), a progress bar,
    completed/total, forecast and author;
  - **Cancel mission** only while the server offers `_links.cancel`;
  - it reloads (debounced) whenever a mission step changes on the live stream.
- **Photo viewer:** clicking the large photo opens it full size (pixelated scaling, so the 320×240
  image stays crisp), with size, time and command. **Download PNG** uses the blob URL, so no
  second request is needed. **Show on map** flies the 3D orbit camera to where it was taken; the
  next camera button returns to the rover.
- **Webhooks dialog** (top bar):
  - register a URL and events (checkboxes in plain words);
  - the secret is shown **once**, with Copy;
  - a list with active/stopped state, deliveries (status, attempts, last HTTP status or error),
    Send test (ping) and Delete.
- **First-visit hints:** four tips (the ~30 s delay and why, click-to-drive, camera, "always the
  past"). They stay away after **Got it** (localStorage, guarded for private windows).

## Acceptance criteria

- [x] Missions listed; cancel via the server's link; updates live.
- [x] Photo full size, download, show on map.
- [x] Webhook register (secret once), list, deliveries, delete.
- [x] Hints on first visit only.
- [x] `npm test` (41) and build green; browser check.

## Verification

- Backend unchanged (last full verify, ticket 26: 211 unit + 179 integration tests).
- Browser (headless Edge), 14 checks, all pass:
  - hints shown, and gone after "Got it" and a reload;
  - a 2-photo mission appeared in the panel and was cancelled with "Cancel mission";
  - the photo viewer opened, with a download link, and "Show on map" closed it;
  - webhook registered with the secret shown once, listed, deliveries opened, then deleted.
