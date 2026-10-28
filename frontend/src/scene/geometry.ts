import type { Heading } from '../api/types';
import { colorize, SURFACE_ROCK } from '../terrain/colorize';

/** Relief is only a few meters on a 64 m site; a little exaggeration makes it readable. */
export const VERTICAL_EXAGGERATION = 1.5;

/** The landing site as the scene needs it: the grid and both maps, one byte per cell. */
export type Grid = {
  width: number;
  height: number;
  metersPerCell: number;
  minElevationMeters: number;
  maxElevationMeters: number;
  /** Heightmap levels 0–255, row-major, row 0 = north. */
  heights: Uint8Array;
  /** Surface codes (0 ground, 128 sand, 255 rock), same layout. */
  surfaces: Uint8Array;
};

/**
 * Centre of grid cell (x, y) in world coordinates. The site is centred on the origin; columns run
 * along +X (east) and rows along +Z, so row 0 (north) is at −Z.
 */
export function cellToWorld(grid: Grid, x: number, y: number): [number, number] {
  return [(x + 0.5 - grid.width / 2) * grid.metersPerCell, (y + 0.5 - grid.height / 2) * grid.metersPerCell];
}

/** World height of one cell centre (cells outside the grid repeat the edge). */
export function cellHeight(grid: Grid, x: number, y: number): number {
  const cx = Math.min(Math.max(x, 0), grid.width - 1);
  const cy = Math.min(Math.max(y, 0), grid.height - 1);
  const level = grid.heights[cy * grid.width + cx] ?? 0;
  const meters = grid.minElevationMeters + (level / 255) * (grid.maxElevationMeters - grid.minElevationMeters);
  return meters * VERTICAL_EXAGGERATION;
}

/** Ground height under any world point: bilinear between the four nearest cell centres (the mesh's own surface). */
export function heightAt(grid: Grid, worldX: number, worldZ: number): number {
  const gx = worldX / grid.metersPerCell + grid.width / 2 - 0.5;
  const gz = worldZ / grid.metersPerCell + grid.height / 2 - 0.5;
  const x0 = Math.floor(gx);
  const z0 = Math.floor(gz);
  const fx = gx - x0;
  const fz = gz - z0;
  const top = lerp(cellHeight(grid, x0, z0), cellHeight(grid, x0 + 1, z0), fx);
  const bottom = lerp(cellHeight(grid, x0, z0 + 1), cellHeight(grid, x0 + 1, z0 + 1), fx);
  return lerp(top, bottom, fz);
}

/**
 * Vertex and index arrays for the terrain: one vertex per cell centre, two triangles per square
 * between four centres, counter-clockwise seen from above. Colours are linear RGB (three.js works
 * in linear space), from the same palette as the 2D map.
 */
export function terrainArrays(grid: Grid): { positions: Float32Array; colors: Float32Array; indices: Uint32Array } {
  const { width, height } = grid;
  const positions = new Float32Array(width * height * 3);
  const colors = new Float32Array(width * height * 3);
  const rgba = colorize(grid.heights, grid.surfaces);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      const [wx, wz] = cellToWorld(grid, x, y);
      positions.set([wx, cellHeight(grid, x, y), wz], i * 3);
      colors.set(
        [srgbToLinear((rgba[i * 4] ?? 0) / 255), srgbToLinear((rgba[i * 4 + 1] ?? 0) / 255), srgbToLinear((rgba[i * 4 + 2] ?? 0) / 255)],
        i * 3,
      );
    }
  }
  const indices = new Uint32Array((width - 1) * (height - 1) * 6);
  let k = 0;
  for (let y = 0; y < height - 1; y++) {
    for (let x = 0; x < width - 1; x++) {
      const a = y * width + x; // north-west
      const b = a + 1; // north-east
      const c = a + width; // south-west
      const d = c + 1; // south-east
      indices.set([a, c, b, b, c, d], k);
      k += 6;
    }
  }
  return { positions, colors, indices };
}

/** Cells the rover cannot enter: drawn as boulders. */
export function rockCells(grid: Grid): Array<[number, number]> {
  const cells: Array<[number, number]> = [];
  for (let i = 0; i < grid.surfaces.length; i++) {
    if (grid.surfaces[i] === SURFACE_ROCK) cells.push([i % grid.width, Math.floor(i / grid.width)]);
  }
  return cells;
}

const HEADING_DEGREES: Record<Heading, number> = { NORTH: 0, EAST: 90, SOUTH: 180, WEST: 270 };

/**
 * Rotation about +Y that turns a model facing −Z (north) to `heading`. Compass degrees run
 * clockwise seen from above, three.js angles counter-clockwise, hence the minus.
 */
export function headingToYaw(heading: Heading): number {
  return -(HEADING_DEGREES[heading] * Math.PI) / 180;
}

/** The signed turn from `from` to `to` the short way round, in (−π, π]. */
export function shortestTurn(from: number, to: number): number {
  const full = 2 * Math.PI;
  let delta = (to - from) % full;
  if (delta <= -Math.PI) delta += full;
  if (delta > Math.PI) delta -= full;
  return delta;
}

/** How far a signal has travelled, 0 at transmission to 1 on arrival, from the real timestamps. */
export function beamProgress(transmittedAt: string, arrivesAt: string, now: number): number {
  const start = Date.parse(transmittedAt);
  const end = Date.parse(arrivesAt);
  if (!(end > start)) return 1;
  return Math.min(1, Math.max(0, (now - start) / (end - start)));
}

export function srgbToLinear(c: number): number {
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}
