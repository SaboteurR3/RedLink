# RedLink

[![CI](https://github.com/SaboteurR3/RedLink/actions/workflows/ci.yml/badge.svg)](https://github.com/SaboteurR3/RedLink/actions/workflows/ci.yml)

**Mission control for a Mars rover, as a REST web service.**

You sit at mission control on Earth and drive a rover on Mars: turn it, drive it around rocks and
craters, take photos, drill samples, keep its battery charged. The catch is the same one NASA has:
**Mars is far away.** Every command takes time to get there, the rover carries it out on its own,
and you only find out what happened when its report comes back. You never see the rover live — only
what it last told you.

The rover is simulated, but the problems are real ones, and solving them properly is what this
project is about:

- **Nothing happens instantly.** Sending a command answers `202 Accepted`, not "done". The command
  is queued, transmitted, executed and reported back, and the screen follows each step live.
- **Messages get lost or arrive twice.** Every hop is retried until it succeeds, and every receiver
  ignores duplicates, so a command never runs twice and no report is ever lost.
- **Nobody can be trusted blindly.** Users log in with roles, risky commands (drilling) need a second
  person's approval, and the rover only obeys messages signed with a shared secret.
- **The link can go down.** During a solar conjunction the Sun blocks the signal; everything waits
  and resumes without losing anything.

The same pattern runs remote control of drones, satellites, IoT devices and EV chargers — Mars is
just the extreme case. RedLink was built as a Master's project for a web-services course at TSU.

## What you can do

- **Drive the rover** on a 3D model of its landing site (Jezero or Gale crater): move, rotate, or
  click a spot on the map and let the planner find a safe route.
- **Plan missions** of up to 20 steps; the plan is simulated before it is sent, so you see where the
  rover will end up and what would fail.
- **Take photos** and **drill rock or soil samples**; photos are drawn from what the rover sees.
- **Watch the rover's world**: battery, day and night (solar charging only works in daylight), dust
  storms, safe mode after a crash.
- **Work as a team**: viewers watch, operators command, flight directors approve drills and declare
  link outages; everything is audited.
- **Get notified**: register a webhook and your own system is called when a command finishes or the
  rover is in trouble.

## How it is built

```
  Browser (React + 3D)  ──REST + live events──►  mission-control (Earth)  ◄──signed packets, delayed──►  rover-sim (Mars)
                                                  Spring Boot + PostgreSQL                               Spring Boot + PostgreSQL
```

| Part | What it is |
|---|---|
| `redlink-mission-control/` | Earth: the public REST API, users and roles, command queue, telemetry, live updates, webhooks (port 8080) |
| `redlink-rover-sim/` | Mars: the rover's flight software — executes commands on the terrain and reports back (port 8081) |
| `redlink-contract/` | The shared "language": command and telemetry formats, rover specs, the terrain maps |
| `frontend/` | The mission-control screen: React, with the 3D view in three.js |

Java 17, Spring Boot 4, Spring Security, PostgreSQL with Flyway, React 19 and Vite.

## Run it

You need **Java 17+**, **Node 22** and **PostgreSQL**.

1. Create the two databases (once):
   ```sh
   psql -U postgres -f scripts/local-db-setup.sql
   ```
2. Start Earth and Mars (two terminals, or run both apps from your IDE with the `local` profile):
   ```sh
   ./mvnw install -DskipTests
   ./mvnw -pl redlink-mission-control spring-boot:run -Dspring-boot.run.profiles=local
   ./mvnw -pl redlink-rover-sim       spring-boot:run -Dspring-boot.run.profiles=local
   ```
3. Start the screen:
   ```sh
   cd frontend
   npm install
   npm run dev
   ```
4. Open <http://localhost:5173> and log in with password `redlink-dev` as **`viewer`**,
   **`operator`** or **`director`**.

API documentation: <http://localhost:8080/swagger-ui.html>.

Docker, configuration, tests and other options: [docs/RUNNING.md](docs/RUNNING.md).

## Read more

| Document | For |
|---|---|
| [Product guide](docs/GUIDE.md) | What RedLink does, its rules, and a test-it-yourself checklist |
| [How it works](docs/HOW-IT-WORKS.md) | Backend developers: modules, a command's path end to end, reliability patterns, real mission vs RedLink |
| [API flows](docs/API-FLOWS.md) | Every API call step by step, with what the screen shows and all status codes |
| [Running](docs/RUNNING.md) | Docker, configuration, tests, webhooks |
| [Write-up](docs/writeup/WRITEUP.md) | The design chapters, limits and future work |
| [Demo script](docs/writeup/DEMO.md) | A 15-minute demo |
| [Architecture spec](docs/specs/redlink/SPEC.md), [decisions](docs/adr), [tickets](docs/specs/redlink/README.md) | How it was designed and built |
