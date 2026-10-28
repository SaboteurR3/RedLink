/** Surface codes in surface.png (see redlink-contract SurfaceType). */
export const SURFACE_GROUND = 0;
export const SURFACE_SAND = 128;
export const SURFACE_ROCK = 255;

/**
 * Turns the two grayscale site maps into Mars colours: low ground dark rust, high ground light
 * ochre, sand paler, rocks near black.
 *
 * @param heights one byte per cell, 0 = lowest, 255 = highest
 * @param surfaces one byte per cell (0 ground, 128 sand, 255 rock)
 * @returns RGBA bytes, 4 per cell
 */
export function colorize(heights: Uint8Array, surfaces: Uint8Array): Uint8ClampedArray<ArrayBuffer> {
  if (heights.length !== surfaces.length) {
    throw new Error('Heightmap and surface map differ in size');
  }
  const rgba = new Uint8ClampedArray(heights.length * 4);
  for (let i = 0; i < heights.length; i++) {
    const h = (heights[i] ?? 0) / 255;
    let r = 92 + h * 140;
    let g = 38 + h * 92;
    let b = 22 + h * 50;
    const surface = surfaces[i];
    if (surface === SURFACE_SAND) {
      r += 25;
      g += 30;
      b += 18;
    } else if (surface === SURFACE_ROCK) {
      r = 38;
      g = 30;
      b = 28;
    }
    rgba[i * 4] = r;
    rgba[i * 4 + 1] = g;
    rgba[i * 4 + 2] = b;
    rgba[i * 4 + 3] = 255;
  }
  return rgba;
}

/** First channel of RGBA image data: the gray value of a grayscale PNG. */
export function grayChannel(rgba: Uint8ClampedArray): Uint8Array {
  const gray = new Uint8Array(rgba.length / 4);
  for (let i = 0; i < gray.length; i++) gray[i] = rgba[i * 4] ?? 0;
  return gray;
}
