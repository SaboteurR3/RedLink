# RedLink demo script (about 15 minutes)

Each scene says what to do, what to point out, and what to do if it goes wrong. Commands are for
Git Bash; the UI is the main stage, and `curl` shows the HTTP details the UI hides.

## Before the audience arrives

1. PostgreSQL is running, with the databases from `scripts/local-db-setup.sql`.
2. Build once, then start both apps (two terminals):
   ```sh
   ./mvnw install -DskipTests
   ./mvnw -pl redlink-mission-control spring-boot:run -Dspring-boot.run.profiles=local
   ./mvnw -pl redlink-rover-sim       spring-boot:run -Dspring-boot.run.profiles=local
   ```
3. Frontend, freshly started (a Vite server that is older than the last `npm install` serves a
   blank page):
   ```sh
   cd frontend && npm run dev     # http://localhost:5173
   ```
4. Browser window A: log in as `operator`. A private window B: log in as `director`. Password:
   `redlink-dev`.
5. A terminal with the helpers:
   ```sh
   API=http://localhost:8080/api/v1
   login() { curl -s -X POST $API/auth/token -H 'Content-Type: application/json' \
     -d "{\"username\":\"$1\",\"password\":\"redlink-dev\"}" | sed 's/.*"access_token":"\([^"]*\)".*/\1/'; }
   OP=$(login operator); DIR=$(login director); VIEW=$(login viewer)
   ```
   Tokens live 15 minutes: run `OP=$(login operator)` again if a call answers 401.
6. Rehearsal check: `REDLINK_URL=http://localhost:8080 node scripts/e2e.mjs` must end with "All
   steps passed". It also leaves the rover idle and its storage in use, which is fine.

Rover in `SAFE_MODE` from an earlier run? Send `REBOOT` from the console first. Battery below
40 %? Send `CHARGE 60` before the audience arrives (one simulated minute = 1 s).

---

## 1. The setting (1 min)

- Window A: the 3D crater. The terrain is built from the **same heightmap and surface files** the
  rover simulation drives on.
- The rover stands where Earth **last heard** it was, not where it "is". Mission control never
  reads the rover's true state.
- Show the Orbit, Follow and Top-down cameras, then the 2D toggle (also the fallback without WebGL).
- Side panels: last telemetry (Mars time), the deep-space link (light time 10 s), photos.

## 2. An asynchronous command (2 min)

Window A: `ROTATE → EAST`, then **Send to rover**.

- The answer comes at once, with status `QUEUED`. Show the same thing in `curl`:
  ```sh
  curl -si -X POST $API/rovers/1/commands -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(date +%s%N)" -d '{"type":"ROTATE","heading":"WEST"}' | sed -n '1p;/^Location/p;/^Retry-After/p'
  ```
  **Point out:** `202 Accepted`, `Location` (the status resource) and `Retry-After`. The command is
  accepted, not done.
- Watch the timeline:
  1. QUEUED for 5 s (the cancel window);
  2. TRANSMITTED: the **beam** flies for 10 s of real light time, with a countdown in the link
     panel;
  3. RECEIVED, EXECUTING, COMPLETED;
  4. 10 s later Earth hears it, and the rover glides on screen.
- **Point out:** the screen moves only when telemetry arrives, pushed over **SSE**. There is no
  polling.

## 3. Idempotency (1 min)

```sh
KEY=demo-$(date +%s)
for i in 1 2; do curl -s -o /dev/null -D - -X POST $API/rovers/1/commands -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"type":"TAKE_PHOTO","camera":"NAVCAM"}' \
  | grep -i '^HTTP\|^Idempotent'; done
curl -s -X POST $API/rovers/1/commands -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" -d '{"type":"REBOOT"}'; echo
```

**Point out:**
- The second call is `Idempotent-Replayed: true`: the same answer byte for byte, and **one**
  photo, not two.
- The same key with another body is `422 idempotency-key-reused`.
- The UI does the same on network errors: one key per click, reused on every retry.

## 4. Cancel and HATEOAS (1 min)

Window A: send `MOVE 1` and press **Cancel** within 5 s. It becomes CANCELLED, and the Cancel button
disappears.

**Point out:**
- The button exists only because the server put `_links.cancel` into the command; the UI never
  decides on its own.
- Once transmitted, cancelling is a conflict:
  ```sh
  curl -s -X POST $API/commands/<id of a COMPLETED command>/cancel -H "Authorization: Bearer $OP"; echo
  ```
  This answers `409 command-not-cancellable` as a Problem Details document.

## 5. Approval and the four-eyes rule (1.5 min)

Window A (operator): send `DRILL_SAMPLE`. It is `PENDING_APPROVAL`.

- Window B (director): **Approve**. The 5 s hold counts from the approval; then it flies.
- Now the director drills and tries to approve their own command:
  ```sh
  ID=$(curl -s -X POST $API/rovers/1/commands -H "Authorization: Bearer $DIR" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(date +%s%N)" -d '{"type":"DRILL_SAMPLE"}' | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
  curl -s -X POST $API/commands/$ID/approve -H "Authorization: Bearer $DIR"; echo        # 403 four-eyes-violation
  curl -s -X POST $API/commands/$ID/reject -H "Authorization: Bearer $DIR" -H 'Content-Type: application/json' -d '{"reason":"demo"}' >/dev/null
  ```

## 6. A mission (2 min)

Window A: **Add to plan** `TAKE_PHOTO MASTCAM`, `ROTATE → NORTH`, `MOVE 3`, then **Send mission**.

- **Point out:**
  - The server simulated the whole plan first (terrain, slope, battery per meter, storage) and
    answered with a **forecast**.
  - The translucent **ghost rover** stands there.
  - The steps run in order, with chips like `M5·2`. When the rover arrives, it stands exactly on
    the ghost.
- If the server refuses this plan because of a rock in the way, that is the next point: plan the
  other direction (`ROTATE → SOUTH`) and send again.
- An impossible plan: `MOVE 20` four times towards the map edge. It is refused with
  `422 mission-plan-infeasible`, naming the step and the meter. **Nothing** was created.
- While the rover still has unfinished commands, a new plan is `409 rover-busy`: a plan starts from
  a known state.

## 7. Photos and content negotiation (1 min)

The photo strip shows the rover's camera view, rendered on "Mars" with Java2D from the terrain.

```sh
P=$(curl -s "$API/rovers/1/photos?size=1" -H "Authorization: Bearer $VIEW" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
curl -si $API/photos/$P -H "Authorization: Bearer $VIEW" | sed -n '1p;/^Content-Type/p;/^Vary/p;/^Cache-Control/p'
curl -si $API/photos/$P -H "Authorization: Bearer $VIEW" -H 'Accept: image/png' -o photo.png -D - | sed -n '1p;/^Content-Type/p;/^Cache-Control/p'
curl -s -o /dev/null -w '%{http_code}\n' $API/photos/$P -H "Authorization: Bearer $VIEW" -H 'Accept: text/plain'
```

**Point out:**
- It is one URL with two representations.
- `Vary: Accept` keeps caches correct.
- The PNG is `immutable` for a year, while the JSON is revalidated.
- An unsupported type is `406`.

## 8. Conditional requests (1 min)

```sh
E=$(curl -si $API/rovers/1 -H "Authorization: Bearer $VIEW" | grep -i '^etag' | cut -d' ' -f2 | tr -d '\r'); echo $E
curl -si $API/rovers/1 -H "Authorization: Bearer $VIEW" -H "If-None-Match: $E" | head -1   # 304, no body
curl -si -X PATCH $API/rovers/1 -H "Authorization: Bearer $OP" -H 'Content-Type: application/merge-patch+json' \
  -d '{"name":"Percy"}' | head -1                                                           # 428 Precondition Required
curl -si -X PATCH $API/rovers/1 -H "Authorization: Bearer $OP" -H 'Content-Type: application/merge-patch+json' \
  -H 'If-Match: "0"' -d '{"name":"Percy"}' | head -1                                       # 412: someone changed it| head -1                                       # 412: someone changed it
```

**Point out:** no lost updates between two operators, and the database `@Version` closes the last
race.

## 9. Solar conjunction (2 min)

Window B (director): **Declare conjunction**. The link panel shows the banner in both windows,
pushed as the SSE `link` event.

- Window A: send `ROTATE → SOUTH`. It stays QUEUED: nothing leaves Earth.
- If telemetry was still on its way, the rover's log shows
  `not delivered (attempt 1: HTTP 503); retrying at …` **30 s** later. That is Earth's
  `Retry-After`, not the rover's 1 s backoff.
- Window B: **End conjunction**. The queued command flies, and the telemetry arrives.

**Point out:** store-and-forward on both ends, with HTTP's own vocabulary: `503` + `Retry-After`.
Nothing is lost.

## 10. Contract and errors (1 min)

- <http://localhost:8080/swagger-ui.html>: the OpenAPI 3.1 document generated from the code. The
  frontend generates its TypeScript types from it (`npm run gen:api`), so a contract break fails
  the build.
- Any of the error answers above: `application/problem+json`, a stable `type` URI, and no stack
  traces.

## 11. Proof (1 min)

```sh
REDLINK_URL=http://localhost:8080 node scripts/e2e.mjs
```

Ten steps, about 40 s, all through the public API. Then the numbers: 203 unit tests and 175
integration tests against real PostgreSQL (`./mvnw verify -Plocal-db`), and 33 frontend tests.

---

## With Docker instead

```sh
cp .env.example .env            # fill in the three secrets and the demo password
docker compose up --build       # → http://localhost:8088
```

The users and password come from `REDLINK_DEMO_USERS_PASSWORD`. The `curl` helpers then use
`API=http://localhost:8088/api/v1`. Swagger UI is at <http://localhost:8088/swagger-ui.html>. The
rover's log is `docker compose logs -f rover-sim`. Not yet rehearsed: the stack has not been
started on the development machine (see the write-up, "Limits").
