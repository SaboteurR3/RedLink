import { Line } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef, type ComponentRef } from 'react';
import { cellHeight, cellToWorld, type Grid } from './geometry';
import { daylight, mixColor, scenePhase } from './sol';

const GAUGE = 0.34; // half the distance between the left and right wheels

/**
 * Two ruts in the sand along the cells the rover reported, one per wheel side. A jump of more than
 * one cell (e.g. a reset of the simulation) starts a new piece instead of a straight line.
 */
export function Tracks({ grid, cells }: { grid: Grid; cells: Array<{ x: number; y: number }> }) {
  const pieces = useMemo(() => {
    const result: Array<Array<{ x: number; y: number }>> = [];
    let current: Array<{ x: number; y: number }> = [];
    for (const cell of cells) {
      const last = current[current.length - 1];
      if (last && last.x === cell.x && last.y === cell.y) continue;
      if (last && Math.abs(last.x - cell.x) + Math.abs(last.y - cell.y) > 1) {
        if (current.length > 1) result.push(current);
        current = [];
      }
      current.push(cell);
    }
    if (current.length > 1) result.push(current);
    return result;
  }, [cells]);

  // Lines are not lit by the scene's lights, so darken them with the daylight by hand.
  const lines = useRef<Array<ComponentRef<typeof Line> | null>>([]);
  useFrame(() => {
    const colour = mixColor('#140a05', '#5b3119', daylight(scenePhase()));
    for (const line of lines.current) line?.material.color.set(colour);
  });

  return (
    <>
      {pieces.flatMap((piece, i) =>
        [-GAUGE, GAUGE].map((side) => (
          <Line
            key={`${i}:${side}`}
            ref={(l) => {
              lines.current[i * 2 + (side < 0 ? 0 : 1)] = l;
            }}
            points={rut(grid, piece, side)} color="#5b3119" lineWidth={2.2} transparent opacity={0.55} />
        )),
      )}
    </>
  );
}

/** One rut: the cell centres shifted sideways (perpendicular to the direction of travel). */
function rut(grid: Grid, piece: Array<{ x: number; y: number }>, side: number): Array<[number, number, number]> {
  return piece.map((cell, i) => {
    const next = piece[Math.min(i + 1, piece.length - 1)] ?? cell;
    const prev = piece[Math.max(i - 1, 0)] ?? cell;
    const dx = next.x - prev.x;
    const dz = next.y - prev.y;
    const length = Math.hypot(dx, dz) || 1;
    const [wx, wz] = cellToWorld(grid, cell.x, cell.y);
    return [wx + (-dz / length) * side, cellHeight(grid, cell.x, cell.y) + 0.03, wz + (dx / length) * side];
  });
}
