/** Default relay pass when no link windows are configured: every 4 minutes, 45 s across the sky. */
export const DECORATIVE_EVERY_MS = 4 * 60_000;
export const DECORATIVE_PASS_MS = 45_000;

/**
 * How far the relay orbiter is across the sky: 0 rising, 1 setting, null when it is below the
 * horizon. With link windows it crosses exactly while a window is open; otherwise it makes a
 * decorative pass on a fixed schedule.
 *
 * @param window the link window state (open, and when that changes), or null if windows are off
 */
export function orbiterProgress(
  nowMs: number,
  window: { open: boolean; nextChangeAt: string | null; durationMs: number } | null,
): number | null {
  if (window && window.nextChangeAt) {
    if (!window.open) return null;
    const closesAt = Date.parse(window.nextChangeAt);
    const opensAt = closesAt - window.durationMs;
    return clamp01((nowMs - opensAt) / window.durationMs);
  }
  const into = ((nowMs % DECORATIVE_EVERY_MS) + DECORATIVE_EVERY_MS) % DECORATIVE_EVERY_MS;
  return into < DECORATIVE_PASS_MS ? into / DECORATIVE_PASS_MS : null;
}

/** Position on an arc across the sky from west to east (progress 0..1). */
export function orbiterPosition(progress: number, radius: number): [number, number, number] {
  const angle = Math.PI * (1 - progress); // west (π) to east (0)
  return [Math.cos(angle) * radius, Math.sin(angle) * radius * 0.6 + 8, -radius * 0.25];
}

/** Intro flight timeline (ms): Earth, the flight, Mars growing, fade to the crater. */
export const INTRO = { earth: 1500, flight: 3500, mars: 1500, fade: 800 } as const;
export const INTRO_TOTAL_MS = INTRO.earth + INTRO.flight + INTRO.mars + INTRO.fade;

/** Where the intro is: the stage and how far through it (0..1). */
export function introStage(elapsedMs: number): { stage: 'earth' | 'flight' | 'mars' | 'fade' | 'done'; t: number } {
  let left = elapsedMs;
  for (const stage of ['earth', 'flight', 'mars', 'fade'] as const) {
    if (left < INTRO[stage]) return { stage, t: left / INTRO[stage] };
    left -= INTRO[stage];
  }
  return { stage: 'done', t: 1 };
}

function clamp01(x: number): number {
  return Math.min(1, Math.max(0, x));
}
