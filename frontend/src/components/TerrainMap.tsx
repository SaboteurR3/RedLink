import { useEffect, useRef, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { Heading, Rover, Site } from '../api/types';
import { colorize } from '../terrain/colorize';
import { loadGrid } from '../terrain/pixels';

const HEADING_DEGREES: Record<Heading, number> = { NORTH: 0, EAST: 90, SOUTH: 180, WEST: 270 };

/**
 * The landing site from the same heightmap and surface files the rover simulation uses, with the
 * rover where Earth last heard it was. It moves only when telemetry arrives.
 */
export function TerrainMap({ api, site, rover }: { api: ApiClient; site: Site; rover: Rover }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      const { heights, surfaces } = await loadGrid(api, site);
      const target = canvas.current;
      if (cancelled || !target) return;
      target.getContext('2d')?.putImageData(new ImageData(colorize(heights, surfaces), site.width, site.height), 0, 0);
    })().catch(() => !cancelled && setError('Terrain could not be loaded'));
    return () => {
      cancelled = true;
    };
  }, [api, site]);

  const { x, y, heading, mode } = rover.lastKnown;
  return (
    <>
      {error && <p className="error">{error}</p>}
      <div className="map-frame">
        <canvas ref={canvas} width={site.width} height={site.height} className="terrain" />
        <svg className="overlay" viewBox={`0 0 ${site.width} ${site.height}`} aria-label="Rover position">
          <circle cx={site.start.x + 0.5} cy={site.start.y + 0.5} r={1.2} className="landing" />
          <g
            className={`rover ${mode === 'SAFE_MODE' ? 'safe' : ''}`}
            style={{ transform: `translate(${x + 0.5}px, ${y + 0.5}px) rotate(${HEADING_DEGREES[heading]}deg)` }}
          >
            <circle r={1.6} className="rover-halo" />
            <polygon points="0,-1.4 1,1 0,0.4 -1,1" />
          </g>
        </svg>
      </div>
      <div className="legend muted">
        <span className="swatch ground" /> ground <span className="swatch sand" /> sand <span className="swatch rock" /> rock
        <span className="swatch landing-dot" /> landing site
      </div>
    </>
  );
}

