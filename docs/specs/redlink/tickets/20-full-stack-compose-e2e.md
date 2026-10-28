# 20 — Full-stack Docker Compose + end-to-end test

Status: ready-for-human

## Scope

Run everything with one command, and prove that the whole system works end to end through its
public front door.

- **Images** (multi-stage, build inside Docker, so only Docker is needed on the host):
  - `redlink-mission-control/Dockerfile` and `redlink-rover-sim/Dockerfile`:
    - JDK 17 build with the Maven wrapper (`-pl <module> -am`, tests skipped: they ran in CI/locally);
    - JRE 17 runtime with Spring Boot's layered extraction (dependencies cached apart from app code);
    - runs as a non-root user, with `-XX:MaxRAMPercentage`.
  - `frontend/Dockerfile`: Node 22 runs `npm ci && npm run build`; nginx 1.27 serves `dist`.
  - Root `.dockerignore` (targets, node_modules, `.env`, IDE files).
- **nginx** (`frontend/nginx.conf`):
  - the SPA with fallback to `index.html`;
  - hashed `/assets/` cached 1 year `immutable`, and `index.html` `no-cache`;
  - reverse proxy to mission-control for `/api/`, `/v3/api-docs` and `/swagger-ui`;
  - the SSE route with buffering off and a long read timeout;
  - security headers (`X-Content-Type-Options`, `Referrer-Policy`, a CSP that allows the inline
    favicon and blob images);
  - `server_tokens off`.

  One origin, so no CORS.
- **Compose** (`docker-compose.yml`): `postgres` (existing init script: two databases, two roles),
  `mission-control`, `rover-sim`, `frontend`.
  - Only the frontend is published (`127.0.0.1:8088`).
  - The apps and the database stay on the internal network, so the HMAC link endpoints
    (`/flight`, `/dsn`) are not reachable from outside at all.
  - The apps wait for a healthy database. Their mutual start order does not matter, because the
    outboxes retry until the other side is up.
  - Secrets come from `.env` with `${VAR:?}` (fail fast if missing); `.env.example` documents them.
- **`demo` profile:** the dev-user seeder runs in `local` and `demo`. In `demo` the password comes
  only from `REDLINK_DEMO_USERS_PASSWORD` (no default in the repository).
- **End-to-end test** (`scripts/e2e.mjs`, Node 22, no dependencies), against `REDLINK_URL`:
  1. log in as operator and director;
  2. open the SSE stream with a ticket;
  3. plan a mission (photo, rotate, photo) and wait for COMPLETED;
  4. check the stream delivered `command` events;
  5. fetch the photo as PNG by content negotiation;
  6. retry the mission submission with the same Idempotency-Key and expect a replay;
  7. check the error contracts: 401 without a token, 404 problem, and 406 for an unsupported
     `Accept` on a photo.

  Exit code 0/1, one line per step.

## Acceptance criteria

- [x] Dockerfiles and compose are complete and consistent with the app configuration (env names, ports, profiles).
- [x] `demo` profile seeds users from the env password; no password default in the repository.
- [x] `scripts/e2e.mjs` passes against the locally running apps.
- [x] `./mvnw verify -Plocal-db` green; frontend tests and build green.
- [ ] `docker compose up --build` + `REDLINK_URL=http://localhost:8088 node scripts/e2e.mjs`
      — **not verifiable on this machine (Docker Desktop hangs)**; to be run where Docker works.

## Checklist

- [x] Dockerfiles ×3, `.dockerignore`, nginx config
- [x] Compose + `.env.example`
- [x] `demo` profile (seeder + `application-demo.yml`)
- [x] `scripts/e2e.mjs`, run against local apps
- [x] README (run with Docker), SPEC, ticket index
- [x] Full build green

## Notes for later tickets

- 21 (write-up): `scripts/e2e.mjs` is a ready demo script; single-instance SSE (PostgreSQL
  LISTEN/NOTIFY for scaling) goes into future work.
- **Still to do where Docker works:** `cp .env.example .env` (fill in the secrets),
  `docker compose up --build`, then `REDLINK_URL=http://localhost:8088 REDLINK_PASSWORD=<demo password> node scripts/e2e.mjs`.
  Never run here: the image builds, nginx config syntax (`nginx -t`), the CSP against the real
  bundle and Swagger UI, the base-image tags (`eclipse-temurin:17-jdk-noble`, `17-jre-noble`,
  `node:22-alpine`, `nginx:1.27-alpine`; floating minor tags, not pinned digests).

## Verification

- `docker compose config` (parses without the daemon): valid; only 8088 and the localhost-bound
  5432 are published.
- `demo` profile, run from the jar against the local database:
  - without `REDLINK_DEMO_USERS_PASSWORD` it refuses to start ("must not be blank");
  - with it, it starts.
  - Found and fixed on the way: an unresolved `${...}` was bound literally, which would have
    seeded a known password; the placeholder now has an empty default, so `@NotBlank` and
    `@Size(min = 8)` stop startup.
- Layered jar extraction checked locally: the four layers, with `JarLauncher` where the
  entrypoint expects it.
- `scripts/e2e.mjs`: all 10 steps pass, twice:
  - against both apps directly (`REDLINK_URL=http://localhost:8080`, mission #3, 29× 304 while
    waiting);
  - through the Vite dev proxy (`http://localhost:5173`, mission #4), so the SSE stream also
    works behind a reverse proxy.
- `./mvnw verify -Plocal-db`: 203 unit + 163 integration tests green.
