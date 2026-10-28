# 02 — Contract: command & telemetry dictionaries, packet signing

Status: ready-for-human
Source spec: ../SPEC.md §3

## Scope

Everything Earth and Mars must agree on, in `redlink-contract` (no Spring; only Jackson
annotations and the Jakarta Validation API):

- **Command dictionary**: `CommandType` (approval required? accepted in safe mode?) and a sealed
  `RoverCommand` with one record per command (`Move`, `Rotate`, `TakePhoto`, `DrillSample`,
  `Charge`, `Reboot`), each carrying its own validation limits.
- **Telemetry dictionary**: `TelemetryType` and a sealed `TelemetryPayload` (`CommandStatus`,
  `RoverState`, `Event`). `PHOTO` is added in ticket 16 together with the photo feature.
- **Packets**: `CommandPacket` (uplink) and `TelemetryPacket` (downlink).
- **Shared value types**: `Heading`, `Camera`, `RoverMode`, `CommandExecutionStatus`, `EventSeverity`.
- **Link authentication**: `PacketSigner` signs `timestamp + "." + body` with HMAC-SHA256.
  Header `X-Link-Signature: t=<epoch seconds>,v1=<base64url>`. Verification is constant-time and
  rejects timestamps outside a tolerance window. Replays inside the window are stopped by packet-id
  dedupe on the receiver (tickets 05 and 11).

### Design decisions

- The command type travels **inside** the command object (`"command": {"type": "MOVE", ...}`), not
  as a sibling field. One source of truth, so a packet can never say `MOVE` with `ROTATE` params.
- The sealed hierarchies make the dictionaries closed: adding a command is a compile error in
  every `switch` that forgets it, on both sides of the link.
- `commandId` lives in `CommandStatus` only, because only command-related telemetry has one.
- Signing covers the exact bytes sent, so the receiver must verify the raw body before parsing it.

## Out of scope

HTTP endpoints and filters that use the signer (tickets 04, 05, 11). Duration estimates
(ticket 09). Photo telemetry (ticket 16).

## Acceptance criteria

- JSON round-trip for every command and every telemetry payload, with the exact wire shape asserted.
- Unknown command type in JSON fails to deserialize.
- Bean Validation rejects out-of-range parameters (for example `MOVE 0`, `MOVE 21`, `CHARGE 0`).
- Signer: valid signature accepted; tampered body, wrong secret, malformed header, expired and
  future timestamps rejected; secret shorter than 32 bytes refused.

## Checklist

- [x] Ticket detailed, SPEC §3 updated to the final wire format
- [x] Contract dependencies (jackson-annotations, jakarta.validation-api; test: jackson-databind, hibernate-validator)
- [x] Value enums
- [x] `CommandType` + `RoverCommand`
- [x] `TelemetryType` + `TelemetryPayload`
- [x] `CommandPacket`, `TelemetryPacket`
- [x] `PacketSigner` + `SignatureVerificationException`
- [x] Tests: JSON round-trips, validation, signer
- [x] `./mvnw -pl redlink-contract verify` green (49 tests); all modules compile
