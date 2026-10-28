# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## Project

RedLink: a Master's web-services project. Spring Boot 4.1 / Java 17 / PostgreSQL / Flyway backend,
React frontend in `frontend/`. The architecture is in `docs/specs/redlink/SPEC.md`; read it before
changing behaviour. The grade is about REST and web-service design, so correctness of HTTP
semantics (status codes, headers, idempotency, caching, concurrency) matters more than features.

## Working on tickets

- Work one ticket at a time from `docs/specs/redlink/tickets/`. Before starting, flesh out the
  ticket (scope, acceptance criteria, checklist) and set `Status: in-progress`; tick the checklist
  as you go; set `Status: ready-for-human` when done and update the index in `docs/specs/redlink/README.md`.
- Do not pull work from later tickets into the current one. Note discoveries in the relevant
  later ticket instead.
- Record non-obvious design decisions as short ADRs in `docs/adr/`.

## Module rules

- `redlink-mission-control` and `redlink-rover-sim` never depend on each other and never share
  tables. They share only `redlink-contract`.
- `redlink-contract` has no Spring dependency: records, enums and small pure utilities only.
- mission-control never reads the rover's true state; it only knows what telemetry reported.

## Spring Boot conventions

- Package by feature (`...missioncontrol.command`, `...missioncontrol.rover`), not by layer.
- Constructor injection only; no field `@Autowired`. Configuration through typed, validated
  `@ConfigurationProperties` records, not scattered `@Value`.
- Controllers are thin: map HTTP to a service call and back. DTOs (records) at the API edge;
  never expose JPA entities in responses.
- Transactions on service methods (`@Transactional`, `readOnly = true` for reads).
  `open-in-view` stays off.
- Schema changes only through new Flyway migrations (`V<n>__description.sql`); never edit an
  applied migration. Hibernate runs with `ddl-auto: validate`.
- Errors are RFC 9457 Problem Details with stable `type` URIs; never leak stack traces or SQL.
- Bean Validation on every request body and parameter.
- Times are `Instant` in UTC; IDs exposed in URLs are not guessable where it matters.

## Lombok

Use Lombok for boilerplate in all Java code (configured in `lombok.config`; `provided` scope, so
it is compile-time only and fine in `redlink-contract`):

- `@Slf4j` instead of a hand-written logger.
- `@RequiredArgsConstructor` for constructor injection (`private final` fields). Write the
  constructor by hand only when it computes something.
- Entities: `@Getter` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, with a
  domain constructor and intention-revealing methods for changes. No `@Setter` or `@Data` on
  entities (`@Data`'s equals/hashCode breaks with Hibernate proxies).
- Exceptions and other classes: `@Getter` on the fields that need accessors.
- Utility classes: `@NoArgsConstructor(access = AccessLevel.PRIVATE)`.
- DTOs, contract messages and value types stay Java `record`s; Lombok is not needed there.

## Security rules

- No secrets in the repository. Defaults that are passwords exist only in `application-local.yml`.
- Every endpoint is authenticated unless explicitly listed as public; authorise by role with
  method security.
- Link endpoints (`/flight/**`, `/dsn/**`) accept only HMAC-signed packets; compare signatures in
  constant time.
- Outbound HTTP (webhooks) must defend against SSRF.
- Only `health` and `info` actuator endpoints are exposed.

## Testing

- Unit tests (`*Test`, surefire) for domain logic and MockMvc slices; integration tests (`*IT`,
  failsafe) against real PostgreSQL, never H2. ITs import `TestcontainersConfiguration` and use
  `@ActiveProfiles("test")`.
- Docker hangs on the main dev machine: run ITs with `./mvnw verify -Plocal-db` (local PostgreSQL,
  schema `it`). ITs must clean the tables they use in `@BeforeEach`.
- Every HTTP-semantics feature gets a test for the exact status code and headers.
- `./mvnw verify` (or `-Plocal-db`) must pass before a ticket is marked ready.
- Persisted `Instant`s are truncated to microseconds (PostgreSQL precision) so a value returned
  now equals the value read back later.
