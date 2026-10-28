import type { ApiClient } from '../api/client';
import type { Site } from '../api/types';
import type { Grid } from '../scene/geometry';
import { grayChannel } from './colorize';

/** The gray values of a grayscale PNG, exactly as stored (no colour management). */
export async function grayPixels(blob: Blob, site: Site): Promise<Uint8Array> {
  const bitmap = await createImageBitmap(blob, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
  const scratch = new OffscreenCanvas(site.width, site.height);
  const context = scratch.getContext('2d');
  if (!context) throw new Error('No 2D canvas');
  context.drawImage(bitmap, 0, 0);
  return grayChannel(context.getImageData(0, 0, site.width, site.height).data);
}

/**
 * Both site maps, fetched with the bearer token (an <img> cannot send it; the browser caches them
 * for a day). These are the very files the rover simulation drives on.
 */
export async function loadGrid(api: ApiClient, site: Site): Promise<Grid> {
  const [heightmap, surface] = await Promise.all([
    api.blob(`/api/v1/sites/${site.id}/heightmap`),
    api.blob(`/api/v1/sites/${site.id}/surface`),
  ]);
  const [heights, surfaces] = await Promise.all([grayPixels(heightmap, site), grayPixels(surface, site)]);
  return {
    width: site.width,
    height: site.height,
    metersPerCell: site.metersPerCell,
    minElevationMeters: site.minElevationMeters,
    maxElevationMeters: site.maxElevationMeters,
    heights,
    surfaces,
  };
}
