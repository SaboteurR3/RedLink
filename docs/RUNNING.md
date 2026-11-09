# Running RedLink — all the options

The [README](../README.md) shows the simple way: local PostgreSQL, the two apps, and the frontend in
dev mode. This page has everything else.

## Run everything with Docker

```sh
cp .env.example .env    # set the passwords and the three secrets (openssl rand -base64 32)
docker compose up --build
```

What goes in `.env`:

| Variable | What it is |
|---|---|
| `POSTGRES_PASSWORD` | Password of the `postgres` user in the database container |
| `REDLINK_JWT_SECRET` | Key that signs login tokens (base64, ≥ 32 bytes) |
| `REDLINK_LINK_SECRET` | HMAC key shared by Earth and the rover for signed packets (base64, ≥ 32 bytes) |
| `REDLINK_DEMO_USERS_PASSWORD` | Password of the users `viewer`, `operator`, `director` |

Open <http://localhost:8088> and log in with `REDLINK_DEMO_USERS_PASSWORD`. nginx serves the UI and
proxies `/api`, `/v3/api-docs` and `/swagger-ui` to mission-control, so
<http://localhost:8088/swagger-ui.html> works too. Only port 8088 is published (on localhost); the
apps and the HMAC link endpoints stay on the internal network. The images build inside Docker, so
nothing but Docker is needed on the host.

The database container publishes port 5432; if PostgreSQL is already installed locally, stop it or
change the port in `docker-compose.yml`.

## Only the database in Docker

```sh
cp .env.example .env
docker compose up -d postgres        # databases mission_control and rover
```

Then start the apps with the `local` profile as in the README.

## From the IDE without any database

Run `TestMissionControlApplication` / `TestRoverSimApplication` (under `src/test`): they start a
throwaway PostgreSQL with Testcontainers (needs Docker).

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

## Trying the API with curl

```sh
curl -s -H 'Content-Type: application/json' \
     -d '{"username":"operator","password":"redlink-dev"}' \
     http://localhost:8080/api/v1/auth/token
# → {"access_token":"eyJ...","token_type":"Bearer","expires_in":900}

curl -s -H "Authorization: Bearer <access_token>" http://localhost:8080/api/v1/auth/me
```

Swagger UI: <http://localhost:8080/swagger-ui.html> (log in with `POST /api/v1/auth/token`, then
paste the token into "Authorize").

## Tests

```sh
./mvnw test                 # unit tests (*Test), no database needed
./mvnw verify               # + integration tests (*IT) on a Testcontainers PostgreSQL (needs Docker)
./mvnw verify -Plocal-db    # + integration tests on the local PostgreSQL instead (no Docker)
```

`-Plocal-db` uses the databases from `scripts/local-db-setup.sql`, in a separate schema `it`, so
your development data is never touched.

Frontend: `cd frontend && npm test` (Vitest) and `npm run build`.

End-to-end, against a running system (Node 22, no dependencies):

```sh
REDLINK_URL=http://localhost:8080 node scripts/e2e.mjs                         # local apps (dev password)
REDLINK_URL=http://localhost:8088 REDLINK_PASSWORD=... node scripts/e2e.mjs   # docker compose
```

It logs in, opens the event stream, plans a mission and waits until the rover has done it, then
checks idempotent replay, 304s, the pushed events, the photo as PNG (and 406), and the error
problems. Exit code 0 when all steps pass.

## Testing webhooks locally

```sh
node scripts/webhook-receiver.mjs                          # listens on http://localhost:9000
WEBHOOK_SECRET=whsec_... node scripts/webhook-receiver.mjs # also verifies the signature
STATUS=503 node scripts/webhook-receiver.mjs               # answers 503: watch the retries
```

In the app: **Webhooks** → URL `http://localhost:9000/hook` → choose events → copy the secret it
shows once. The `local` profile allows `http://localhost`; anywhere else webhooks must be public HTTPS.
