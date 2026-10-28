# 33 — Distributed tracing across the link

Status: ready-for-human

## Scope

- Micrometer Tracing over OpenTelemetry in both services (`spring-boot-starter-opentelemetry`):
  - sampling at 100 %;
  - the trace id in the log lines;
  - OTLP export only when `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` is set.
- Contract:
  - `TraceContext.TRACEPARENT_PATTERN` and `traceId()`;
  - optional, validated `traceparent` in `CommandPacket` and `TelemetryPacket`, left out of the
    JSON when null;
  - convenience constructors without it, so untraced code and tests are unchanged.
- mission-control:
  - `commands.trace_parent` (V14) records the request's trace;
  - mission steps share the planning request's trace;
  - the transmitter puts it in the packet;
  - the dispatcher sends inside an `uplink send` span;
  - `RoverLinkClient` adds the `traceparent` header.
- rover-sim:
  - `received_commands.trace_parent` (V6);
  - `rover receive` and `rover start/step <TYPE>` spans continue the packet's trace;
  - every telemetry packet carries the current trace;
  - the downlink dispatcher sends inside a `downlink send` span;
  - `EarthLinkClient` adds the header.
- ADR `docs/adr/trace-context-inside-link-packets.md`.

## Acceptance criteria

- [x] A caller's W3C `traceparent` is continued and stored with the command (HTTP-level test).
- [x] The trace reaches the rover in the packet and in the header, even though a scheduler sends it later.
- [x] Every report about a traced command carries the same trace id; untraced packets are unchanged.
- [x] Full verify green; one trace id seen in both services' logs for a real command.

## Notes for later tickets

- A Jaeger service in docker-compose (under a `tracing` profile) would show the waterfall. It is not
  added because Docker cannot be run on this machine to check it; the property above is all it needs.
- Found and fixed: the OpenTelemetry starter also pushes **metrics** to `localhost:4318` every minute,
  which would fail every minute with no collector. It is now off by default
  (`management.otlp.metrics.export.enabled: false`); restarting confirmed the push is gone.

## Verification

- New tests:
  - contract: the traced packet's JSON and round trip, and the traceparent rules (version,
    all-zero ids, uppercase, junk);
  - `CommandsIT`: a caller's `traceparent` header is continued and stored with the command;
  - `UplinkIT`: the request's trace is in the packet sent by the scheduler later, and the header names
    the `uplink send` child span; an untraced command's packet has no field;
  - rover-sim `CommandExecutionIT`: every report about a traced command (RECEIVED, EXECUTING,
    position, COMPLETED) carries Earth's trace id, each from its own span.
- Full `./mvnw verify -Plocal-db`: unit 77 + 101 + 36, integration 163 + 27, all green.
- Live, against both apps: ROTATE for rover 2 (command #62) was sent with
  `traceparent: 00-5e1f0c0ffee0000000000000000a1b2c-…`. The trace id appears on:
  - mission-control "queued command 62";
  - rover-sim "Received command 62", in a different process, after the scheduler's light-time
    delay;
  - mission-control's ingestion of RECEIVED, EXECUTING and COMPLETED, each with its own span id.
