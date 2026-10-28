import { colorize } from '../terrain/colorize';
import { heightAt, srgbToLinear, VERTICAL_EXAGGERATION, type Grid } from './geometry';

/** Vertices per cell along each axis in the site mesh. */
export const SITE_DETAIL = 3;
/** Deepest dip of the site's small-scale relief, in meters (dips only, see `microRelief`). */
export const MICRO_RELIEF = 0.1;
/** How far the landscape reaches from the site centre, in meters. */
export const LANDSCAPE_RADIUS = 700;
/** The landscape sits this far below the site where they overlap, so the site always covers it. */
export const SKIRT_DEPTH = 0.2;

const RIM_RADIUS = 430;
const RIM_HEIGHT = 80;
/** A layered peak south-east of the site (like Gale's Mount Sharp); +Z is south. */
const PEAK = { x: 90, z: 290, radius: 150, height: 95 };
/** A few impact craters on the plain: centre, radius, depth. */
const CRATERS: Array<{ x: number; z: number; r: number; depth: number }> = [
  { x: -150, z: -60, r: 34, depth: 7 },
  { x: 120, z: -170, r: 22, depth: 5 },
  { x: -90, z: 170, r: 48, depth: 9 },
  { x: 230, z: 40, r: 18, depth: 4 },
  { x: -260, z: -220, r: 60, depth: 11 },
];

/** Deterministic value in [0, 1) per lattice point. */
export function hash2(x: number, y: number): number {
  let h = Math.imul(x, 374761393) + Math.imul(y, 668265263);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) & 0xffff) / 65536;
}

/**
 * Smooth value noise in [0, 1). With a `period` (in lattice units) it repeats, so a texture made
 * from it tiles without seams.
 */
export function valueNoise(x: number, z: number, period = 0): number {
  const x0 = Math.floor(x);
  const z0 = Math.floor(z);
  const fx = smooth(x - x0);
  const fz = smooth(z - z0);
  const at = (ix: number, iz: number) => (period > 0 ? hash2(mod(ix, period), mod(iz, period)) : hash2(ix, iz));
  const top = lerp(at(x0, z0), at(x0 + 1, z0), fx);
  const bottom = lerp(at(x0, z0 + 1), at(x0 + 1, z0 + 1), fx);
  return lerp(top, bottom, fz);
}

/** Several octaves of value noise, each twice as fine and half as strong; still in [0, 1). */
export function fbm(x: number, z: number, octaves: number, period = 0): number {
  let sum = 0;
  let weight = 0;
  let amplitude = 1;
  let scale = 1;
  for (let i = 0; i < octaves; i++) {
    sum += amplitude * valueNoise(x * scale, z * scale, period * scale);
    weight += amplitude;
    amplitude /= 2;
    scale *= 2;
  }
  return sum / weight;
}

/**
 * Small dents in the site's ground (0 down to −MICRO_RELIEF). Only dips, so tracks, markers and
 * the rover, which sit on the bilinear surface, are never buried.
 */
export function microRelief(worldX: number, worldZ: number): number {
  return -MICRO_RELIEF * fbm(worldX / 1.6, worldZ / 1.6, 3);
}

/**
 * Height of the landscape around the site. At the site's edge it continues the site's own surface
 * (a skirt's depth below it), then blends within 60 m into hills, craters, the crater rim on the
 * horizon and a layered peak.
 */
export function landscapeHeight(grid: Grid, worldX: number, worldZ: number): number {
  const { cx, cz, outside } = clampToSite(grid, worldX, worldZ);
  const edge = heightAt(grid, cx, cz) - SKIRT_DEPTH;
  if (outside === 0) return edge;

  const plain = ((grid.minElevationMeters + grid.maxElevationMeters) / 2) * VERTICAL_EXAGGERATION;
  const hills = (fbm(worldX / 55, worldZ / 55, 4) - 0.45) * 12 * smoothstep(20, 140, outside);
  const r = Math.hypot(worldX, worldZ);
  const rimProfile = r < RIM_RADIUS ? Math.exp(-(((r - RIM_RADIUS) / 140) ** 2)) : 1 - 0.3 * smoothstep(RIM_RADIUS, 680, r);
  const rim = RIM_HEIGHT * rimProfile * (0.6 + 0.8 * fbm(worldX / 80 + 17, worldZ / 80 - 5, 5));
  const mountain = peak(worldX, worldZ);
  let craters = 0;
  for (const c of CRATERS) craters += crater(Math.hypot(worldX - c.x, worldZ - c.z), c.r, c.depth);
  const far = plain + hills + rim + mountain + craters;
  return lerp(edge, far, smoothstep(0, 60, outside));
}

/**
 * Linear RGB colour of the landscape: the site's own colour at its edge, then rust plains, dark
 * crater rims, a dusty rim wall and the peak's pale sediment layers.
 */
export function landscapeColor(
  grid: Grid,
  siteColors: Float32Array,
  worldX: number,
  worldZ: number,
  h: number,
): [number, number, number] {
  const { cx, cz, outside } = clampToSite(grid, worldX, worldZ);
  const n = fbm(worldX / 9, worldZ / 9, 3);
  const patches = fbm(worldX / 70 + 3, worldZ / 70 + 9, 3);
  // Plains: rust, with darker basalt patches.
  let color = mixRgb(PLAIN, BASALT, smoothstep(0.55, 0.75, patches));
  // Higher ground gets dustier, the rim wall darker at its foot.
  color = mixRgb(color, DUST, smoothstep(10, 60, h) * 0.6);
  const peakShare = peak(worldX, worldZ) / PEAK.height;
  if (peakShare > 0.08) {
    const layer = 0.5 + 0.5 * Math.sin(h * 0.9 + n * 2);
    color = mixRgb(color, mixRgb(SEDIMENT_DARK, SEDIMENT_LIGHT, layer), smoothstep(0.08, 0.3, peakShare));
  }
  const shade = 0.85 + 0.3 * n;
  color = [color[0] * shade, color[1] * shade, color[2] * shade];
  return mixRgb(siteColorAt(grid, siteColors, cx, cz), color, smoothstep(0, 40, outside));
}

/** Linear RGB per cell (3 floats per cell), from the same palette as the 2D map. */
export function siteColors(grid: Grid): Float32Array {
  const rgba = colorize(grid.heights, grid.surfaces);
  const colors = new Float32Array(grid.width * grid.height * 3);
  for (let i = 0; i < grid.width * grid.height; i++) {
    for (let c = 0; c < 3; c++) colors[i * 3 + c] = srgbToLinear((rgba[i * 4 + c] ?? 0) / 255);
  }
  return colors;
}

/**
 * The site mesh at SITE_DETAIL vertices per cell: the bilinear surface plus small dips, colours
 * blended between cells and varied a little, UVs in world meters / 4 for the ground texture.
 */
export function siteArrays(grid: Grid, detail = SITE_DETAIL) {
  const cols = (grid.width - 1) * detail + 1;
  const rows = (grid.height - 1) * detail + 1;
  const cellColors = siteColors(grid);
  const positions = new Float32Array(cols * rows * 3);
  const colors = new Float32Array(cols * rows * 3);
  const uvs = new Float32Array(cols * rows * 2);
  for (let j = 0; j < rows; j++) {
    for (let i = 0; i < cols; i++) {
      const v = j * cols + i;
      const wx = (i / detail + 0.5 - grid.width / 2) * grid.metersPerCell;
      const wz = (j / detail + 0.5 - grid.height / 2) * grid.metersPerCell;
      positions.set([wx, heightAt(grid, wx, wz) + microRelief(wx, wz), wz], v * 3);
      const base = siteColorAt(grid, cellColors, wx, wz);
      const shade = 0.88 + 0.24 * fbm(wx / 2.5, wz / 2.5, 3);
      const tint = fbm(wx / 12 + 40, wz / 12, 2) - 0.5;
      colors.set([base[0] * shade * (1 + tint * 0.25), base[1] * shade, base[2] * shade * (1 - tint * 0.2)], v * 3);
      uvs.set([wx / 4, wz / 4], v * 2);
    }
  }
  return {
    positions,
    colors,
    uvs,
    indices: gridIndices(cols, rows),
    cols,
    rows,
  };
}

/**
 * The landscape: a square grid out to LANDSCAPE_RADIUS, its spacing growing with distance (fine
 * where the camera usually is, coarse at the horizon). Squares wholly under the site are left out.
 */
export function landscapeArrays(grid: Grid, segments = 200) {
  const n = segments + 1;
  const coords = Array.from({ length: n }, (_, i) => {
    const u = (i / segments) * 2 - 1;
    return Math.sign(u) * LANDSCAPE_RADIUS * Math.abs(u) ** 1.8;
  });
  const cellColors = siteColors(grid);
  const positions = new Float32Array(n * n * 3);
  const colors = new Float32Array(n * n * 3);
  const uvs = new Float32Array(n * n * 2);
  const inside = new Uint8Array(n * n);
  const halfW = ((grid.width - 1) / 2) * grid.metersPerCell - 1;
  const halfH = ((grid.height - 1) / 2) * grid.metersPerCell - 1;
  for (let j = 0; j < n; j++) {
    for (let i = 0; i < n; i++) {
      const v = j * n + i;
      const wx = coords[i] ?? 0;
      const wz = coords[j] ?? 0;
      const h = landscapeHeight(grid, wx, wz);
      positions.set([wx, h, wz], v * 3);
      colors.set(landscapeColor(grid, cellColors, wx, wz, h), v * 3);
      uvs.set([wx / 8, wz / 8], v * 2);
      inside[v] = Math.abs(wx) < halfW && Math.abs(wz) < halfH ? 1 : 0;
    }
  }
  const all = gridIndices(n, n);
  const kept: number[] = [];
  for (let k = 0; k < all.length; k += 6) {
    const quad = [all[k], all[k + 1], all[k + 2], all[k + 5]] as number[];
    if (quad.every((v) => inside[v])) continue;
    for (let t = 0; t < 6; t++) kept.push(all[k + t] ?? 0);
  }
  return { positions, colors, uvs, indices: Uint32Array.from(kept) };
}

/** A tileable grain texture (gray levels 0.72–1, one byte per pixel) for the ground's colour and bump. */
export function grainPixels(size = 256): Uint8Array {
  const pixels = new Uint8Array(size * size);
  const period = 16;
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const n = fbm((x / size) * period, (y / size) * period, 4, period);
      pixels[y * size + x] = Math.round(255 * (0.72 + 0.28 * n));
    }
  }
  return pixels;
}

/** Two triangles per square of a cols × rows vertex grid, counter-clockwise seen from above. */
function gridIndices(cols: number, rows: number): Uint32Array {
  const indices = new Uint32Array((cols - 1) * (rows - 1) * 6);
  let k = 0;
  for (let y = 0; y < rows - 1; y++) {
    for (let x = 0; x < cols - 1; x++) {
      const a = y * cols + x;
      const b = a + 1;
      const c = a + cols;
      const d = c + 1;
      indices.set([a, c, b, b, c, d], k);
      k += 6;
    }
  }
  return indices;
}

function peak(worldX: number, worldZ: number): number {
  const d = Math.hypot(worldX - PEAK.x, worldZ - PEAK.z) / PEAK.radius;
  const rugged = 0.7 + 0.6 * fbm(worldX / 35, worldZ / 35, 5);
  return PEAK.height * Math.exp(-(d * d) * 1.6) * rugged;
}

/** A bowl with a raised rim, 0 far away. */
function crater(distance: number, radius: number, depth: number): number {
  const t = distance / radius;
  if (t < 1) return -depth * (1 - t * t) + depth * 0.35 * t ** 6;
  return depth * 0.35 * Math.exp(-(((t - 1) / 0.35) ** 2));
}

/** The nearest point of the site (between its edge cell centres) and how far outside it the point is. */
function clampToSite(grid: Grid, worldX: number, worldZ: number) {
  const halfW = ((grid.width - 1) / 2) * grid.metersPerCell;
  const halfH = ((grid.height - 1) / 2) * grid.metersPerCell;
  const cx = Math.min(Math.max(worldX, -halfW), halfW);
  const cz = Math.min(Math.max(worldZ, -halfH), halfH);
  return { cx, cz, outside: Math.hypot(worldX - cx, worldZ - cz) };
}

/** Bilinear site colour at a world point inside the site. */
function siteColorAt(grid: Grid, cellColors: Float32Array, worldX: number, worldZ: number): [number, number, number] {
  const gx = Math.min(Math.max(worldX / grid.metersPerCell + grid.width / 2 - 0.5, 0), grid.width - 1);
  const gz = Math.min(Math.max(worldZ / grid.metersPerCell + grid.height / 2 - 0.5, 0), grid.height - 1);
  const x0 = Math.min(Math.floor(gx), grid.width - 2);
  const z0 = Math.min(Math.floor(gz), grid.height - 2);
  const fx = gx - x0;
  const fz = gz - z0;
  const at = (x: number, z: number, c: number) => cellColors[(z * grid.width + x) * 3 + c] ?? 0;
  const out: [number, number, number] = [0, 0, 0];
  for (let c = 0; c < 3; c++) {
    out[c] = lerp(lerp(at(x0, z0, c), at(x0 + 1, z0, c), fx), lerp(at(x0, z0 + 1, c), at(x0 + 1, z0 + 1, c), fx), fz);
  }
  return out;
}

const PLAIN = linear('#9a5434');
const BASALT = linear('#5a3a2e');
const DUST = linear('#b88560');
const SEDIMENT_DARK = linear('#8c5a40');
const SEDIMENT_LIGHT = linear('#c79a72');

function linear(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16);
  return [srgbToLinear(((v >> 16) & 255) / 255), srgbToLinear(((v >> 8) & 255) / 255), srgbToLinear((v & 255) / 255)];
}

function mixRgb(a: [number, number, number], b: [number, number, number], t: number): [number, number, number] {
  return [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
}

function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = Math.min(Math.max((x - edge0) / (edge1 - edge0), 0), 1);
  return t * t * (3 - 2 * t);
}

function smooth(t: number): number {
  return t * t * (3 - 2 * t);
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function mod(a: number, n: number): number {
  return ((a % n) + n) % n;
}
