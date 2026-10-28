import { describe, expect, it } from 'vitest';
import { DECORATIVE_EVERY_MS, DECORATIVE_PASS_MS, INTRO_TOTAL_MS, introStage, orbiterPosition, orbiterProgress } from './orbit';

describe('relay orbiter', () => {
  const windows = (open: boolean, nextChangeAt: string) => ({ open, nextChangeAt, durationMs: 45_000 });

  it('crosses exactly while a link window is open', () => {
    const closes = Date.parse('2026-10-28T12:00:45Z');
    expect(orbiterProgress(closes - 45_000, windows(true, '2026-10-28T12:00:45Z'))).toBe(0);
    expect(orbiterProgress(closes - 22_500, windows(true, '2026-10-28T12:00:45Z'))).toBe(0.5);
    expect(orbiterProgress(closes, windows(false, '2026-10-28T12:02:00Z'))).toBeNull();
  });

  it('makes a decorative pass when windows are off', () => {
    expect(orbiterProgress(DECORATIVE_PASS_MS / 2, null)).toBe(0.5);
    expect(orbiterProgress(DECORATIVE_PASS_MS + 1, null)).toBeNull();
    expect(orbiterProgress(DECORATIVE_EVERY_MS, null)).toBe(0);
  });

  it('rises in the west and sets in the east', () => {
    expect(orbiterPosition(0, 100)[0]).toBeCloseTo(-100);
    expect(orbiterPosition(1, 100)[0]).toBeCloseTo(100);
    expect(orbiterPosition(0.5, 100)[1]).toBeGreaterThan(orbiterPosition(0.1, 100)[1]);
  });
});

describe('intro timeline', () => {
  it('walks through the stages and ends', () => {
    expect(introStage(0)).toEqual({ stage: 'earth', t: 0 });
    expect(introStage(1500 + 1750).stage).toBe('flight');
    expect(introStage(1500 + 1750).t).toBeCloseTo(0.5);
    expect(introStage(INTRO_TOTAL_MS)).toEqual({ stage: 'done', t: 1 });
  });
});
