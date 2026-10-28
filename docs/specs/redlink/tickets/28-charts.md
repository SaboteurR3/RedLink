# 28 — Telemetry charts

Status: ready-for-human

## Scope

A **History** panel in the side column: battery, storage and temperature over Mars time.

- **Data:** `GET /rovers/{id}/telemetry?type=ROVER_STATE&size=200` (the existing history API),
  plus every new `rover` event from the live stream as a point (deduplicated by time, bounded to
  500).
- **Form** (following the dataviz guidance):
  - three small charts, one per measure, each with its own axis (never two scales on one chart);
  - one series per chart, so no legend box: the title names it;
  - a 2 px line in the reference palette's dark slot 1 (`#3987e5`), validated against our panel
    colour `#161a22`: lightness band, chroma and ≥ 3:1 contrast all pass;
  - a recessive grid; percent axes fixed at 0–100, temperature on its own data range;
  - the latest value as a direct label;
  - text in text colours, never the series colour.
- **Interaction:** a crosshair and dot follow the mouse, with the value and time in the caption.
  A **Table** toggle shows the last 30 reports as numbers (the accessible view).
- **Honesty:** the line breaks where reports are more than 10 minutes apart (e.g. the apps were
  off), so it never draws a slope through a gap.
- Pure, tested geometry (`charts/series.ts`): domain, scaling, gap-aware path, nearest point,
  ordered/deduplicated insert.

## Acceptance criteria

- [x] Charts load from history and grow live; hover tooltip; table view.
- [x] Palette validated; one axis per chart; gaps are not drawn as data.
- [x] `npm test` (45) and build green; rendered and checked visually.

## Verification

- Screenshot of the panel against the running apps: three charts, crosshair and caption
  "27.5 % · 12:25:08", direct labels "46 %" and "−20 °C". The first screenshot showed a line drawn
  through last night's gap; fixed with the gap break (test added).
