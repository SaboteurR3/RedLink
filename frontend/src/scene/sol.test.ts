import { describe, expect, it } from 'vitest';
import { daylight, dusk, mixColor, SOL_MS, solClock, solPhase, sunDirection, sunlight, untilSunriseMs } from './sol';

describe('sol cycle', () => {
  it('phase wraps with the wall clock', () => {
    expect(solPhase(0)).toBe(0);
    expect(solPhase(SOL_MS / 2)).toBe(0.5);
    expect(solPhase(SOL_MS * 3 + SOL_MS / 4)).toBe(0.25);
  });

  it('the sun rises in the east, stands high at noon and sets in the west', () => {
    const [rx, ry] = sunDirection(0.25);
    const [, noonY] = sunDirection(0.5);
    const [sx, sy] = sunDirection(0.75);
    expect(rx).toBeGreaterThan(0.9);
    expect(ry).toBeCloseTo(0);
    expect(noonY).toBeGreaterThan(0.9);
    expect(sx).toBeLessThan(-0.9);
    expect(sy).toBeCloseTo(0);
    expect(sunDirection(0)[1]).toBeLessThan(-0.9); // midnight: under the ground
  });

  it('daylight is 1 at noon, 0 at midnight, and dusk peaks at the horizon', () => {
    expect(daylight(0.5)).toBe(1);
    expect(daylight(0)).toBe(0);
    expect(daylight(0.75)).toBeGreaterThan(0);
    expect(daylight(0.75)).toBeLessThan(1);
    expect(dusk(0.75)).toBeCloseTo(1);
    expect(dusk(0.5)).toBe(0);
  });

  it('solar power follows the sun like the rover computes it', () => {
    expect(sunlight(0.5)).toBeCloseTo(1);
    expect(sunlight(0.25 + 1 / 12)).toBeCloseTo(0.5); // 30° above the horizon
    expect(sunlight(0.25)).toBeCloseTo(0);
    expect(sunlight(0.9)).toBe(0);
    expect(sunlight(0)).toBe(0);
    expect(untilSunriseMs(0)).toBe(SOL_MS / 4);
    expect(untilSunriseMs(0.75)).toBe(SOL_MS / 2);
    expect(untilSunriseMs(0.5)).toBe(0);
  });

  it('reads like a clock', () => {
    expect(solClock(0)).toBe('Sol 00:00');
    expect(solClock(0.5)).toBe('Sol 12:00');
    expect(solClock(0.6)).toBe('Sol 14:24');
  });

  it('mixes colours', () => {
    expect(mixColor('#000000', '#ffffff', 0.5)).toBe('#808080');
    expect(mixColor('#c99a72', '#c99a72', 0.3)).toBe('#c99a72');
  });
});
