import { describe, expect, it } from 'vitest';
import {
  beamProgress,
  cellHeight,
  cellToWorld,
  headingToYaw,
  heightAt,
  rockCells,
  shortestTurn,
  terrainArrays,
  VERTICAL_EXAGGERATION,
  type Grid,
} from './geometry';

// 3 × 2 cells, 2 m per cell, 0–10 m. Row 0 (north): levels 0, 51, 255; row 1: all 0. Cell (2,0) is rock.
const grid: Grid = {
  width: 3,
  height: 2,
  metersPerCell: 2,
  minElevationMeters: 0,
  maxElevationMeters: 10,
  heights: new Uint8Array([0, 51, 255, 0, 0, 0]),
  surfaces: new Uint8Array([0, 128, 255, 0, 0, 0]),
};

describe('scene geometry', () => {
  it('centres the site and puts north at −Z', () => {
    expect(cellToWorld(grid, 0, 0)).toEqual([-2, -1]);
    expect(cellToWorld(grid, 2, 1)).toEqual([2, 1]);
  });

  it('scales levels to meters with the exaggeration', () => {
    expect(cellHeight(grid, 1, 0)).toBeCloseTo(2 * VERTICAL_EXAGGERATION);
    expect(cellHeight(grid, 2, 0)).toBeCloseTo(10 * VERTICAL_EXAGGERATION);
    expect(cellHeight(grid, 9, 9)).toBe(0); // clamped to the edge cell
  });

  it('interpolates the ground between cell centres', () => {
    const [x0, z] = cellToWorld(grid, 0, 0);
    const [x1] = cellToWorld(grid, 1, 0);
    expect(heightAt(grid, x0, z)).toBeCloseTo(0);
    expect(heightAt(grid, (x0 + x1) / 2, z)).toBeCloseTo(VERTICAL_EXAGGERATION); // halfway to 2 m
  });

  it('builds one vertex per cell and two triangles per square', () => {
    const { positions, colors, indices } = terrainArrays(grid);
    expect(positions).toHaveLength(6 * 3);
    expect(colors).toHaveLength(6 * 3);
    expect(Array.from(indices)).toEqual([0, 3, 1, 1, 3, 4, 1, 4, 2, 2, 4, 5]);
    expect(positions[2 * 3 + 1]).toBeCloseTo(15); // (2,0) at 10 m × 1.5
    expect(colors.every((c) => c >= 0 && c <= 1)).toBe(true);
  });

  it('finds the rocks', () => {
    expect(rockCells(grid)).toEqual([[2, 0]]);
  });

  it('turns headings into yaw and always turns the short way', () => {
    expect(headingToYaw('NORTH')).toBeCloseTo(0);
    expect(headingToYaw('EAST')).toBeCloseTo(-Math.PI / 2);
    expect(shortestTurn(headingToYaw('WEST'), headingToYaw('NORTH'))).toBeCloseTo(-Math.PI / 2);
    expect(shortestTurn(headingToYaw('NORTH'), headingToYaw('WEST'))).toBeCloseTo(Math.PI / 2);
    expect(Math.abs(shortestTurn(0, Math.PI))).toBeCloseTo(Math.PI);
  });

  it('moves the beam with the real transmission and arrival times', () => {
    const sent = '2026-10-27T12:00:00Z';
    const arrives = '2026-10-27T12:00:10Z';
    expect(beamProgress(sent, arrives, Date.parse(sent) - 1000)).toBe(0);
    expect(beamProgress(sent, arrives, Date.parse(sent) + 2500)).toBeCloseTo(0.25);
    expect(beamProgress(sent, arrives, Date.parse(arrives) + 1)).toBe(1);
  });
});
