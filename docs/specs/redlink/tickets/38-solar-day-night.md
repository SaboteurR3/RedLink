# 38 — Solar charging follows day and night

Status: ready-for-human

## Scope

The rover is solar powered, but CHARGE gave full power at any hour. The day and night shown in the
3D view existed only in the browser.

- **One Mars clock for everyone:**
  - `MarsClock` in the contract: a 12-minute sol, the phase as a pure function of the time, the
    sunlight fraction and the time until sunrise;
  - the browser's sky already uses the same formula (`sol.ts`), so the sky and the charging agree;
  - `GET /specs` gains `solSeconds`.
- **Charging:**
  - each CHARGE minute gives `CHARGE_PER_MINUTE × sunlight` (× the dust-storm factor);
  - sunlight is the sun's height: 1 at noon, less in the morning and evening, 0 from sunset to sunrise;
  - CHARGE at night still runs (it is not refused) but gains nothing while it is dark.
  - When a CHARGE minute falls in the dark (its first minute, or the first after sunset), the
    rover sends one `NO_SUNLIGHT` WARNING event saying when the sun rises.
- **Earth:**
  - the mission planner cannot know when a planned CHARGE will run on Mars (link delay, queue),
    so its forecast stays the full-sun upper bound; documented in the SPEC;
  - the console shows a night hint when CHARGE is chosen.

## Acceptance criteria

- [x] MarsClock: phase, sunlight (noon 1, sunrise/sunset 0, night 0), time until sunrise; unit tests.
- [x] FlightRules: charge scales with sunlight, 0 at night, storm factor on top; unit tests.
- [x] Rover IT: CHARGE at noon gains, at night gains nothing and sends one NO_SUNLIGHT event.
- [x] `/specs` has `solSeconds`.
- [x] Frontend: the same sunlight formula (test), console night hint; type check, Vitest, build.
- [x] `./mvnw verify -Plocal-db` green.

## Checklist

- [x] Contract MarsClock + test
- [x] Rover: Conditions.sunlight, FlightRules, CommandStepper (+ event), tests and ITs fixed to a daytime clock
- [x] mission-control: specs solSeconds, planner doc
- [x] Frontend: sol.ts sunlight + test, console hint, Specs type
- [x] SPEC, GUIDE, index

## Verification

- New tests:
  - `MarsClockTest` (phase, sunlight, time until sunrise);
  - `FlightRulesTest.chargingFollowsTheSunAndTheWeather` (noon, low sun, night, with and
    without a storm);
  - `RandomEventsIT`: charging at noon gains the full rate; at night it gains nothing and warns
    once ("sunrise in about 179 s"); a charge running into sunset warns once, as the dark begins.
  - The storm-charging IT now runs at noon. The test clock's T0 is midnight on the Mars clock.
  - `SpecsIT`: `solSeconds` is 720.
  - `sol.test.ts`: the browser's `sunlight` and `untilSunriseMs` match `MarsClock`.
- Full `./mvnw verify -Plocal-db`: mission-control 101 + 169, rover-sim 45 + 32, contract green.
  Frontend: Vitest 62, type check, build. API schema regenerated (`solSeconds`).
- Battery values are kept to two decimals, so a quarter-rate charge in a low sun gains 0.13, not 0.125.
