import { useEffect, useRef } from 'react';
import type { Heading } from '../api/types';
import { colorize } from '../terrain/colorize';
import type { Grid } from './geometry';

const HEADING_DEGREES: Record<Heading, number> = { NORTH: 0, EAST: 90, SOUTH: 180, WEST: 270 };

/**
 * The site from above in the 2D map's colours, in a corner of the 3D view: where the rover is and
 * which way it faces, the target and the photo being shown. A click drives there, like a click on
 * the terrain.
 */
export function Minimap({
  grid,
  rover,
  target,
  focus,
  onCellClick,
}: {
  grid: Grid;
  rover: { x: number; y: number; heading: Heading };
  target: { x: number; y: number } | null;
  focus: { x: number; y: number } | null;
  onCellClick?: (x: number, y: number) => void;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    canvas.current
      ?.getContext('2d')
      ?.putImageData(new ImageData(colorize(grid.heights, grid.surfaces), grid.width, grid.height), 0, 0);
  }, [grid]);

  return (
    <div className="minimap" title={onCellClick ? 'Click to drive there' : undefined}>
      <canvas ref={canvas} width={grid.width} height={grid.height} />
      <svg
        className={onCellClick ? 'clickable' : ''}
        viewBox={`0 0 ${grid.width} ${grid.height}`}
        aria-label="Minimap"
        onClick={(event) => {
          if (!onCellClick) return;
          const box = event.currentTarget.getBoundingClientRect();
          const x = Math.floor(((event.clientX - box.left) / box.width) * grid.width);
          const y = Math.floor(((event.clientY - box.top) / box.height) * grid.height);
          if (x >= 0 && y >= 0 && x < grid.width && y < grid.height) onCellClick(x, y);
        }}
      >
        {focus && <circle className="minimap-focus" cx={focus.x + 0.5} cy={focus.y + 0.5} r={2} />}
        {target && <circle className="plan-target" cx={target.x + 0.5} cy={target.y + 0.5} r={1.2} />}
        <g style={{ transform: `translate(${rover.x + 0.5}px, ${rover.y + 0.5}px) rotate(${HEADING_DEGREES[rover.heading]}deg)` }}>
          <polygon className="minimap-rover" points="0,-2.4 1.7,1.7 0,0.7 -1.7,1.7" />
        </g>
      </svg>
    </div>
  );
}
