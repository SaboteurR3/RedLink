import { describe, expect, it } from 'vitest';
import { addPoint, domain, linePath, nearestIndex, scaleY, type Box } from './series';

const box: Box = { width: 110, height: 60, left: 10, right: 0, top: 0, bottom: 10 };

describe('chart series', () => {
  it('fixed domains stay fixed; data domains get a little air', () => {
    expect(domain([20, 80], [0, 100])).toEqual([0, 100]);
    expect(domain([-30, -10])).toEqual([-32, -8]);
    expect(domain([-20, -20])).toEqual([-22, -18]); // flat line still gets a visible band
  });

  it('scales time to x and value to y (up is more)', () => {
    expect(linePath([0, 100], [0, 100], [0, 100], [0, 100], box)).toBe('M10.0,50.0 L110.0,0.0');
    expect(scaleY(50, [0, 100], box)).toBe(25);
    expect(linePath([0, 10, 100], [0, 0, 0], [0, 100], [0, 100], box, 50)).toBe('M10.0,50.0 L20.0,50.0 M110.0,50.0');
  });

  it('finds the point nearest in time', () => {
    const ts = [0, 10, 20, 30];
    expect(nearestIndex(ts, 14)).toBe(1);
    expect(nearestIndex(ts, 16)).toBe(2);
    expect(nearestIndex(ts, -5)).toBe(0);
    expect(nearestIndex(ts, 99)).toBe(3);
    expect(nearestIndex([], 5)).toBe(-1);
  });

  it('keeps points in time order, without repeats, and bounded', () => {
    const p = (t: number) => ({ t, battery: 50, storage: 0, temperature: -20 });
    let points = addPoint([], p(20));
    points = addPoint(points, p(10));
    points = addPoint(points, p(20));
    expect(points.map((x) => x.t)).toEqual([10, 20]);
    expect(addPoint([p(1), p(2), p(3)], p(4), 3).map((x) => x.t)).toEqual([2, 3, 4]);
  });
});
