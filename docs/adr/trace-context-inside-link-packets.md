# Trace context inside link packets, not only in HTTP headers

Status: accepted (ticket 33)

## Context

A command crosses several boundaries in time, not just between processes:
1. the operator's request stores it;
2. a scheduler transmits it after the hold;
3. another scheduler sends it to Mars a light-time later;
4. the rover stores it;
5. the rover's executor runs it over seconds or minutes;
6. the rover's downlink scheduler reports on it;
7. mission control ingests the report and pushes it to browsers.

HTTP header propagation (W3C `traceparent`) only links the two ends of one HTTP call. Every
scheduler would start a new, unrelated trace.

## Decision

- Use Micrometer Tracing over OpenTelemetry (`spring-boot-starter-opentelemetry`, version managed by
  Spring Boot). Every request is sampled; log lines carry the trace id. Spans are **exported only
  when an OTLP endpoint is configured** (`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`),
  so nothing tries to reach a collector by default.
- The W3C `traceparent` is **stored with the work** and **travels inside the signed packets**:
  - `commands.trace_parent` holds the request's trace;
  - `CommandPacket.traceparent` carries it to the rover, where `received_commands.trace_parent`
    keeps it;
  - `TelemetryPacket.traceparent` carries it back to Earth.
  
  Work done later continues that trace in a new span (`LinkTracing.inSpan`): `uplink send`,
  `rover receive`, `rover start/step <TYPE>`, `downlink send`.
- **The packet field is the source of truth; the HTTP header is a convenience.** The link clients
  also send the header, so the receiving server's own span joins the trace. The packet field is
  inside the HMAC-signed body, survives store-and-forward and retries, and cannot be swapped on the
  way.
- The field is optional and validated (`00-<32 hex>-<16 hex>-<2 hex>`, no all-zero ids, lowercase
  only). JSON leaves it out when null, so untraced packets are byte-for-byte what they were before.
  Reports that no command caused (weather, idle ticks) start their own traces.
- The Boot 4 modules here have no `RestClient` auto-configuration, so the link clients add the
  header themselves from the current span.

## Consequences

- One trace id shows up in both services' logs for the whole life of a command, and in a trace
  viewer (Jaeger, Tempo) it becomes one waterfall across two processes and several schedulers when
  export is on.
- The rover side creates spans only when it does work (not on idle executor ticks).
- Trusting a client's `traceparent` header only affects which trace a span joins. It is validated
  and never used for any decision.
