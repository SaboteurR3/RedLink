import { describe, expect, it } from 'vitest';
import { colorize, grayChannel, SURFACE_GROUND, SURFACE_ROCK, SURFACE_SAND } from './colorize';

describe('colorize', () => {
  it('higher ground is lighter, rocks are dark, every pixel opaque', () => {
    const rgba = colorize(new Uint8Array([0, 255, 128]), new Uint8Array([SURFACE_GROUND, SURFACE_GROUND, SURFACE_ROCK]));

    const red = (i: number) => rgba[i * 4]!;
    expect(red(1)).toBeGreaterThan(red(0));
    expect(red(2)).toBeLessThan(red(0));
    expect([3, 7, 11].map((i) => rgba[i])).toEqual([255, 255, 255]);
  });

  it('sand is paler than ground at the same height', () => {
    const rgba = colorize(new Uint8Array([100, 100]), new Uint8Array([SURFACE_GROUND, SURFACE_SAND]));

    expect(rgba[4 + 1]!).toBeGreaterThan(rgba[1]!);
  });

  it('rejects maps of different sizes', () => {
    expect(() => colorize(new Uint8Array(4), new Uint8Array(3))).toThrow();
  });

  it('reads the gray value from RGBA image data', () => {
    expect(grayChannel(new Uint8ClampedArray([7, 7, 7, 255, 200, 200, 200, 255]))).toEqual(new Uint8Array([7, 200]));
  });
});
