import { describe, expect, it } from 'vitest';
import { heightAt, type Grid } from './geometry';
import {
  fbm,
  grainPixels,
  landscapeArrays,
  landscapeHeight,
  MICRO_RELIEF,
  microRelief,
  siteArrays,
  SKIRT_DEPTH,
  valueNoise,
} from './relief';

// 4 × 3 cells, 2 m per cell, 0–10 m, a slope rising to the east.
const grid: Grid = {
  width: 4,
  height: 3,
  metersPerCell: 2,
  minElevationMeters: 0,
  maxElevationMeters: 10,
  heights: new Uint8Array([0, 60, 120, 255, 0, 60, 120, 255, 0, 60, 120, 255]),
  surfaces: new Uint8Array(12),
};

describe('relief', () => {
  it('noise stays in [0, 1), is deterministic and tiles with a period', () => {
    for (let i = 0; i < 200; i++) {
      const n = fbm(i * 0.37, i * 1.13, 4);
      expect(n).toBeGreaterThanOrEqual(0);
      expect(n).toBeLessThan(1);
    }
    expect(valueNoise(3.3, 7.1)).toBe(valueNoise(3.3, 7.1));
    expect(valueNoise(2.4, 5.6, 8)).toBeCloseTo(valueNoise(2.4 + 8, 5.6 - 8, 8), 10);
  });

  it('joins the site at its edge, a skirt below it', () => {
    const eastEdge = 3; // the east cell centres are 3 m east of the centre
    for (const z of [-2, 0, 2]) {
      expect(landscapeHeight(grid, eastEdge, z)).toBeCloseTo(heightAt(grid, eastEdge, z) - SKIRT_DEPTH, 6);
      // A step outside, it has barely started to change.
      expect(Math.abs(landscapeHeight(grid, eastEdge + 0.5, z) - landscapeHeight(grid, eastEdge, z))).toBeLessThan(0.1);
    }
    // Under the site it stays below the site.
    expect(landscapeHeight(grid, 0, 0)).toBeCloseTo(heightAt(grid, 0, 0) - SKIRT_DEPTH, 6);
  });

  it('rises into the crater rim on the horizon', () => {
    expect(landscapeHeight(grid, 0, -430)).toBeGreaterThan(30);
  });

  it('only dents the site, never raises it above the surface the rover uses', () => {
    for (let i = 0; i < 100; i++) {
      const r = microRelief(i * 0.71, -i * 0.29);
      expect(r).toBeLessThanOrEqual(0);
      expect(r).toBeGreaterThanOrEqual(-MICRO_RELIEF);
    }
    const { positions, cols, rows, indices, uvs } = siteArrays(grid, 3);
    expect([cols, rows]).toEqual([10, 7]);
    expect(positions).toHaveLength(10 * 7 * 3);
    expect(uvs).toHaveLength(10 * 7 * 2);
    expect(indices).toHaveLength(9 * 6 * 6);
    // Two triangles per square, counter-clockwise seen from above.
    expect(Array.from(siteArrays(grid, 1).indices.slice(0, 6))).toEqual([0, 4, 1, 1, 4, 5]);
    for (let v = 0; v < cols * rows; v++) {
      const [x, y, z] = [positions[v * 3] ?? 0, positions[v * 3 + 1] ?? 0, positions[v * 3 + 2] ?? 0];
      expect(y).toBeLessThanOrEqual(heightAt(grid, x, z) + 1e-6);
    }
  });

  it('leaves out the landscape squares hidden under the site', () => {
    const { positions, indices, colors } = landscapeArrays(grid, 200);
    expect(positions).toHaveLength(201 * 201 * 3);
    expect(indices.length).toBeLessThan(200 * 200 * 6);
    expect(indices.length % 3).toBe(0);
    expect(colors.every((c) => c >= 0 && c <= 1.5)).toBe(true);
  });

  it('makes a grain texture between 0.72 and 1', () => {
    const pixels = grainPixels(32);
    expect(pixels).toHaveLength(32 * 32);
    expect(Math.min(...pixels)).toBeGreaterThanOrEqual(183);
  });
});
