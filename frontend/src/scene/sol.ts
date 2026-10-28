/**
 * The Mars day ("sol") for the scene, compressed so a demo sees dawn, noon, dusk and night. It is a
 * pure function of the wall clock, so every viewer sees the same time of day.
 */
export const SOL_MS = 12 * 60_000;

/** 0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset. */
export function solPhase(nowMs: number, solMs = SOL_MS): number {
  return (((nowMs % solMs) + solMs) % solMs) / solMs;
}

/** Sun direction (unit vector, y up): rises in the east (+X), sets in the west (−X), tilted south. */
export function sunDirection(phase: number): [number, number, number] {
  const angle = (phase - 0.25) * 2 * Math.PI; // 0 at sunrise, π at sunset
  const x = Math.cos(angle);
  const y = Math.sin(angle);
  const z = 0.35; // a little to the south (+Z)
  const length = Math.hypot(x, y, z);
  return [x / length, y / length, z / length];
}

/** 0 at night, 1 in full daylight, smooth through dawn and dusk. */
export function daylight(phase: number): number {
  const height = sunDirection(phase)[1];
  return smoothstep(-0.12, 0.25, height);
}

/**
 * What the rover's solar panels get, as a share of full power: the sun's height, 1 at noon, 0 from
 * sunset to sunrise. The same formula as `MarsClock.sunlight` on the rover, so the hint matches.
 */
export function sunlight(phase: number): number {
  return Math.max(0, Math.sin((phase - 0.25) * 2 * Math.PI));
}

/** Milliseconds until the sun rises; 0 while it is up. */
export function untilSunriseMs(phase: number, solMs = SOL_MS): number {
  if (phase >= 0.25 && phase < 0.75) return 0;
  return Math.round((phase < 0.25 ? 0.25 - phase : 1.25 - phase) * solMs);
}

/** How much dusk blue to mix in: strongest with the sun just above or below the horizon. */
export function dusk(phase: number): number {
  const height = sunDirection(phase)[1];
  return Math.max(0, 1 - Math.abs(height) / 0.22);
}

/** "Sol 14:20": the local solar time of the scene. */
export function solClock(phase: number): string {
  const minutes = Math.floor(phase * 24 * 60 + 1e-9); // 0.6 × 1440 is 863.999… in floating point
  return `Sol ${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Linear blend of two hex colours (#rrggbb); t from 0 (a) to 1 (b). */
export function mixColor(a: string, b: string, t: number): string {
  const pa = parseInt(a.slice(1), 16);
  const pb = parseInt(b.slice(1), 16);
  const channel = (shift: number) => Math.round(((pa >> shift) & 255) * (1 - t) + ((pb >> shift) & 255) * t);
  return `#${((channel(16) << 16) | (channel(8) << 8) | channel(0)).toString(16).padStart(6, '0')}`;
}

function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)));
  return t * t * (3 - 2 * t);
}

/**
 * The phase the scene shows now. `?sol=0.8` in the address pins it (for demos and screenshots at a
 * chosen time of day); otherwise it follows the wall clock.
 */
export function scenePhase(nowMs = Date.now()): number {
  const fixed = typeof location === 'undefined' ? null : new URLSearchParams(location.search).get('sol');
  const value = fixed === null ? NaN : Number(fixed);
  return Number.isFinite(value) ? ((value % 1) + 1) % 1 : solPhase(nowMs);
}

/** `?storm=1` in the address shows a dust storm whatever the weather (for demos and screenshots). */
export function demoStorm(): boolean {
  return typeof location !== 'undefined' && new URLSearchParams(location.search).get('storm') === '1';
}
