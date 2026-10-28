# RedLink

[![CI](https://github.com/SaboteurR3/RedLink/actions/workflows/ci.yml/badge.svg)](https://github.com/SaboteurR3/RedLink/actions/workflows/ci.yml)

An asynchronous REST platform for commanding a simulated Mars rover over a delayed deep-space link.

Operators on Earth send commands through a REST API. Each command travels over a simulated link
with real delay, a separate rover program executes it, and the result comes back as delayed
telemetry. The same pattern runs remote command-and-control for drones, IoT devices, EV chargers
and satellites; Mars is the extreme case.

- Architecture: [docs/specs/redlink/SPEC.md](docs/specs/redlink/SPEC.md)
- **Product guide** (what RedLink does, its rules, and a test-it-yourself checklist): [docs/GUIDE.md](docs/GUIDE.md)
- **Write-up** (the nine design chapters, limits and future work): [docs/writeup/WRITEUP.md](docs/writeup/WRITEUP.md)
- **Demo script** (15 minutes; its curl scenes dry-run against the running apps): [docs/writeup/DEMO.md](docs/writeup/DEMO.md)
- Decision records: [docs/adr/](docs/adr)
- Tickets and progress: [docs/specs/redlink/README.md](docs/specs/redlink/README.md)

## Repository layout

| Path | What |
|---|---|
| `redlink-contract/` | Command and telemetry dictionaries shared by both apps (plain jar) |
| `redlink-mission-control/` | Earth: public REST API, Spring Boot, port 8080 |
| `redlink-rover-sim/` | Mars: rover flight software simulator, Spring Boot, port 8081 |
| `frontend/` | Mission-control UI (React + Vite + React Three Fiber), nginx image |
| `docker/`, `docker-compose.yml`, `*/Dockerfile` | The full stack in containers |
| `scripts/` | Local database setup, end-to-end test (`e2e.mjs`) |
| `docs/` | Spec, tickets, decision records |

## Requirements

Java 17+ and Node 22 for development; Docker for the full stack (and optionally for Postgres and the
integration tests). Maven comes with the wrapper.

## Run everything with Docker

```sh
cp .env.example .env    # set the passwords and the three secrets (openssl rand -base64 32)
docker compose up --build
```

Open <http://localhost:8088> and log in as `viewer`, `operator` or `director` with
`REDLINK_DEMO_USERS_PASSWORD`. nginx serves the UI and proxies `/api`, `/v3/api-docs` and
`/swagger-ui` to mission-control, so <http://localhost:8088/swagger-ui.html> works too. Only port
8088 is published (on localhost); the apps and the HMAC link endpoints stay on the internal network.
The images build inside Docker, so nothing but Docker is needed on the host.

## Run locally

```sh
cp .env.example .env                 # then edit the passwords
docker compose up -d                 # PostgreSQL with databases mission_control and rover

./mvnw -pl redlink-mission-control spring-boot:run -Dspring-boot.run.profiles=local
./mvnw -pl redlink-rover-sim       spring-boot:run -Dspring-boot.run.profiles=local
```

Run `./mvnw install -DskipTests` once first so the apps can resolve `redlink-contract`.

Health: <http://localhost:8080/actuator/health> and <http://localhost:8081/actuator/health>.

No Docker? Create the databases on a locally installed PostgreSQL instead:
`psql -U postgres -f scripts/local-db-setup.sql`.

The `local` profile creates three users, all with the password `redlink-dev`:
`viewer` (VIEWER), `operator` (OPERATOR) and `director` (FLIGHT_DIRECTOR).

```sh
curl -s -H 'Content-Type: application/json' \
     -d '{"username":"operator","password":"redlink-dev"}' \
     http://localhost:8080/api/v1/auth/token
# → {"access_token":"eyJ...","token_type":"Bearer","expires_in":900}

curl -s -H "Authorization: Bearer <access_token>" http://localhost:8080/api/v1/auth/me
```

API documentation: <http://localhost:8080/swagger-ui.html> (log in with `POST /api/v1/auth/token`,
then paste the token into "Authorize").

Mission-control screen (needs both apps running):

```sh
cd frontend
npm install
npm run dev        # http://localhost:5173 — log in as operator / redlink-dev
```

## Configuration outside `local`

| Variable | App | What |
|---|---|---|
| `MISSION_CONTROL_DB_URL`, `_USERNAME`, `_PASSWORD` | mission-control | Database |
| `ROVER_DB_URL`, `_USERNAME`, `_PASSWORD` | rover-sim | Database |
| `REDLINK_JWT_SECRET` | mission-control | base64, ≥ 32 bytes (`openssl rand -base64 32`) |
| `REDLINK_LINK_SECRET` | both (same value) | base64, ≥ 32 bytes |
| `REDLINK_CORS_ALLOWED_ORIGINS` | mission-control | comma-separated frontend origins |
| `REDLINK_ROVER_URL` / `REDLINK_EARTH_URL` | mission-control / rover-sim | where the other side of the link is |
| `REDLINK_DEMO_USERS_PASSWORD` | mission-control, `demo` profile | password of the seeded users (≥ 8 characters, no default) |

The apps refuse to start if a secret is missing or too short.

Without docker-compose, run `TestMissionControlApplication` / `TestRoverSimApplication`
(under `src/test`) from the IDE: they start a throwaway Postgres with Testcontainers.

## Build and test

```sh
./mvnw test                 # unit tests (*Test), no database needed
./mvnw verify               # + integration tests (*IT) on a Testcontainers PostgreSQL (needs Docker)
./mvnw verify -Plocal-db    # + integration tests on the local PostgreSQL instead (no Docker)
```

`-Plocal-db` uses the databases from `scripts/local-db-setup.sql`, in a separate schema `it`, so
your development data is never touched.

End-to-end, against a running system (Node 22, no dependencies):

```sh
REDLINK_URL=http://localhost:8088 REDLINK_PASSWORD=... node scripts/e2e.mjs   # docker compose
REDLINK_URL=http://localhost:8080 node scripts/e2e.mjs                         # local apps (dev password)
```

It logs in, opens the event stream, plans a mission and waits until the rover has done it, then
checks idempotent replay, 304s, the pushed events, the photo as PNG (and 406), and the error
problems. Exit code 0 when all steps pass.
