# 01 — Monorepo skeleton

Status: ready-for-human
Source spec: ../SPEC.md §2, §9, §11

## Scope

- Turn the generated single-module project into a monorepo: parent `redlink-pom`
  (packaging `pom`, Spring Boot 4.1 parent, Java 17) with modules `redlink-contract`,
  `redlink-mission-control`, `redlink-rover-sim`. `frontend/` lives in the same repository
  (scaffolded in ticket 13).
- Both apps start against PostgreSQL with Flyway enabled and Hibernate in `validate` mode.
- `docker-compose.yml` for local Postgres: one container, two databases (`mission_control`,
  `rover`), each owned by its own login role (least privilege: a service cannot read the other's
  data). Credentials come from `.env` (git-ignored); `.env.example` is committed.
- Production-safe defaults in `application.yml`: no open-in-view, no error details in
  responses, only `health` and `info` actuator endpoints, graceful shutdown, Problem Details on.
- Maven Enforcer: Java 17+, Maven 3.9+, no duplicate dependency versions.
- Repository hygiene: `.gitignore`, `.editorconfig`, `.gitattributes`, README, AGENTS.md / CLAUDE.md.

## Out of scope

Security configuration (ticket 04), any tables (from ticket 04/05 on), frontend code (ticket 13).

## Acceptance criteria

- `./mvnw verify` builds all modules.
- `docker compose up -d` then each app starts and `/actuator/health` returns `UP`.
- Context-load tests run against Testcontainers Postgres (skipped when Docker is not running).

## Checklist

- [x] Spec + ticket backlog in `docs/specs/redlink/`
- [x] Parent pom + module poms
- [x] Application classes, `application.yml`, profiles
- [x] docker-compose + Postgres init script, `.env.example`
- [x] Context-load tests (Testcontainers)
- [x] README, AGENTS.md, CLAUDE.md, `.editorconfig`, `.gitignore`
- [x] `./mvnw verify` green (Docker not running: context-load tests were skipped)
- [x] Verified on a local PostgreSQL 17 (scripts/local-db-setup.sql): both apps `UP`, cross-database access denied, `/actuator/env` 404
- [x] Context-load tests run for real since ticket 05 (`MissionControlApplicationIT`, `RoverSimApplicationIT`, `-Plocal-db`)
