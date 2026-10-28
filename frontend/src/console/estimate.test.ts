import { describe, expect, it } from 'vitest';
import type { LastKnownState, Specs } from '../api/types';
import { estimate, formatBattery, quarterTurns } from './estimate';

const specs: Specs = {
  maxSlopeDegrees: 30,
  moveCostPerMeterGround: 1,
  moveCostPerMeterSand: 2,
  rotateCost: 0.2,
  photoCost: 0.5,
  photoStorage: 2,
  drillCost: 8,
  drillMinBattery: 30,
  drillStorage: 5,
  chargePerMinute: 1,
  dustStormChargeFactor: 0.25,
  moveSecondsPerMeter: 1,
  rotateSecondsPerQuarterTurn: 2,
  photoSeconds: 3,
  drillSeconds: 15,
  rebootSeconds: 5,
  solSeconds: 720,
};
const rover: LastKnownState = {
  x: 10,
  y: 10,
  heading: 'WEST',
  batteryPercent: 50,
  temperatureCelsius: -20,
  storageUsedPercent: 97,
  mode: 'NOMINAL',
  reportedAt: '2026-10-28T10:00:00Z',
};
const link = { oneWayDelaySeconds: 10, queueHoldSeconds: 5 };

describe('estimate', () => {
  it('turns the short way, like the rover', () => {
    expect(quarterTurns('WEST', 'NORTH')).toBe(1);
    expect(quarterTurns('NORTH', 'WEST')).toBe(1);
    expect(quarterTurns('NORTH', 'SOUTH')).toBe(2);
    expect(quarterTurns('EAST', 'EAST')).toBe(0);
  });

  it('a drive costs a range (ground to sand) and the result shows after hold + light time both ways + driving', () => {
    const e = estimate({ type: 'MOVE', distanceMeters: 3 }, rover, specs, link);
    expect(e).toMatchObject({ marsSeconds: 3, batteryMin: -6, batteryMax: -3, seeResultSeconds: 5 + 20 + 3, warnings: [] });
    expect(formatBattery(e)).toBe('−3 to −6 %');
  });

  it('warns when the drive is longer than the battery allows', () => {
    const low = { ...rover, batteryPercent: 4 };
    expect(estimate({ type: 'MOVE', distanceMeters: 5 }, low, specs, link).warnings[0]).toMatch(/Not enough battery for 5 m/);
    expect(estimate({ type: 'MOVE', distanceMeters: 3 }, low, specs, link).warnings[0]).toMatch(/may run out .* sand/);
  });

  it('says when a rotation would do nothing', () => {
    const e = estimate({ type: 'ROTATE', heading: 'WEST' }, rover, specs, link);
    expect(e.marsSeconds).toBe(0);
    expect(e.warnings).toEqual(['The rover already faces WEST: nothing would happen.']);
  });

  it('photo and drill check storage; drill checks battery and needs approval', () => {
    expect(estimate({ type: 'TAKE_PHOTO', camera: 'NAVCAM' }, rover, specs, link).warnings).toEqual([]);
    const drill = estimate({ type: 'DRILL_SAMPLE' }, { ...rover, batteryPercent: 25 }, specs, link);
    expect(drill.warnings).toEqual([
      'Drilling needs 30 % battery; the last report says 25 %.',
      'Drilling waits for another flight director to approve it.',
      'Storage is 97 % full: this needs 5 % more.',
    ]);
  });

  it('charging gains at most up to 100 %, and its duration is unknown to Earth', () => {
    const e = estimate({ type: 'CHARGE', minutes: 80 }, rover, specs, link);
    expect(e).toMatchObject({ marsSeconds: null, batteryMin: 50, batteryMax: 50, seeResultSeconds: null });
    expect(formatBattery(e)).toBe('+50 %');
  });

  it('in safe mode everything but REBOOT is flagged', () => {
    const safe = { ...rover, mode: 'SAFE_MODE' as const };
    expect(estimate({ type: 'TAKE_PHOTO', camera: 'NAVCAM' }, safe, specs, link).warnings[0]).toMatch(/only REBOOT/);
    expect(estimate({ type: 'REBOOT' }, safe, specs, link).warnings).toEqual([]);
  });
});
