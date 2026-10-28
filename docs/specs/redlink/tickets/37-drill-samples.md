# 37 — Drill samples: rock or soil, with a mark on the map

Status: ready-for-human

## Scope

Until now DRILL_SAMPLE only cost battery and storage: nothing showed where or what was drilled.

- **The rover decides what it drills:**
  - if the cell in front of it (its heading) is a rock, it drills the rock (`ROCK`);
  - otherwise it drills the soil just in front of its wheels (`SOIL`).
  - The rule is a pure function in `FlightRules`; drilling costs and checks stay the same.
- **New telemetry type `SAMPLE`:**
  - contract `TelemetryPayload.Sample(commandId, material, x, y, heading)`, where x, y and heading
    are where the rover stood, like `Photo`;
  - new enum `SampleMaterial` (SOIL, ROCK);
  - the rover sends it in the same transaction as the step that completes the drill.
- **mission-control:**
  - stores it like other telemetry (migration V16 widens the type check);
  - `GET /rovers/{id}/telemetry?type=SAMPLE` lists the history;
  - a new SSE event `sample` carries each one live.
- **Frontend:**
  - loads the sample history and listens for `sample`, with a toast when a sample arrives;
  - the 3D map marks each sample: a dark drill hole in the ground in front of where the rover
    stood (soil), or a pale drill scar with dust at the foot of the rock it faced (rock).
- Rocks stay obstacles: drilling takes a sample, it does not remove the rock.

## Acceptance criteria

- [x] FlightRules: rock ahead → ROCK, free ground, sand or the map edge ahead → SOIL.
- [x] Rover IT: a completed drill sends one SAMPLE with the right material and position.
- [x] mission-control IT: a SAMPLE report is stored, listed by type and streamed as `sample`.
- [x] Contract JSON round-trip of the new payload.
- [x] Frontend: marks from history and live; type check, Vitest, build green.
- [x] `./mvnw verify -Plocal-db` green.

## Checklist

- [x] Contract: SampleMaterial, TelemetryType.SAMPLE, TelemetryPayload.Sample, test
- [x] Rover: FlightRules.sampleMaterial + test; CommandStepper emits SAMPLE; IT
- [x] mission-control: V16, TelemetryService, EventType.SAMPLE, SampleMessage; IT
- [x] Frontend: types, stream event, MissionControl state + toast, scene marks
- [x] OpenAPI schema regenerated
- [x] SPEC, GUIDE, index

## Verification

- New tests:
  - `TelemetryJsonTest`: the SAMPLE wire format;
  - `FlightRulesTest.drillTakesTheRockAheadOtherwiseTheSoil` (rock, ground, sand, map edge);
  - `CommandExecutionIT`: a drill facing a rock sends a ROCK sample and the rock stays; on open
    ground it sends a SOIL sample;
  - `TelemetryIT.drillSampleIsStoredListedByTypeAndStreamed`.
- Full `./mvnw verify -Plocal-db`: mission-control 101 unit + 169 integration, rover-sim 40 + 29,
  contract green.
- Frontend: Vitest 61, type check clean, build OK. The OpenAPI schema was regenerated (the
  telemetry `type` filter now lists SAMPLE).
- Browser (GPU), with two injected samples near the rover on jezero-sim: the scar sits on the side
  of the rock facing the rover, with dust and a flag; the soil hole and its flag are in front of
  the rover.
- Drills done before this ticket sent no SAMPLE report, so they have no mark.
- SPEC's DRILL_SAMPLE row said "fails if terrain is rock", which was never implemented. It now
  describes the rock-or-soil rule.
