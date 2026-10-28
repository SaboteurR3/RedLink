import { Line } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import type { Mesh } from 'three';
import type { PlanPreview } from '../api/types';
import { cellHeight, cellToWorld, type Grid } from './geometry';

const LIFT = 0.18; // keeps the line just above the ground

/**
 * A previewed plan drawn on the terrain: green where the rover can go, ending in a ring where it
 * would stop; red up to and on the cell where the plan would fail.
 */
export function PathPreview({ grid, preview }: { grid: Grid; preview: PlanPreview }) {
  const points = useMemo(
    () =>
      preview.path.map((p) => {
        const [wx, wz] = cellToWorld(grid, p.x, p.y);
        return [wx, cellHeight(grid, p.x, p.y) + LIFT, wz] as [number, number, number];
      }),
    [grid, preview],
  );
  const fail = preview.problem;
  const failAt = fail && fail.x !== null && fail.y !== null ? cellToWorld(grid, fail.x, fail.y) : null;
  const last = points[points.length - 1];
  const color = preview.feasible ? '#4fbf7f' : '#e3b341';

  return (
    <group>
      {points.length > 1 && <Line points={points} color={color} lineWidth={3} dashed dashSize={0.5} gapSize={0.25} />}
      {last && <EndRing position={last} color={preview.feasible ? '#4fbf7f' : '#e3b341'} />}
      {failAt && fail?.x != null && fail.y != null && (
        <>
          {last && (
            <Line
              points={[last, [failAt[0], cellHeight(grid, fail.x, fail.y) + LIFT, failAt[1]]]}
              color="#e5534b"
              lineWidth={3}
            />
          )}
          <FailMarker position={[failAt[0], cellHeight(grid, fail.x, fail.y) + 0.5, failAt[1]]} />
        </>
      )}
    </group>
  );
}

function EndRing({ position, color }: { position: [number, number, number]; color: string }) {
  return (
    <mesh position={position} rotation-x={-Math.PI / 2}>
      <ringGeometry args={[0.32, 0.45, 32]} />
      <meshBasicMaterial color={color} toneMapped={false} />
    </mesh>
  );
}

/** A pulsing red box on the cell where the plan would fail. */
function FailMarker({ position }: { position: [number, number, number] }) {
  const box = useRef<Mesh>(null);
  useFrame((state) => {
    if (box.current) box.current.scale.setScalar(1 + 0.15 * Math.sin(state.clock.elapsedTime * 6));
  });
  return (
    <mesh ref={box} position={position}>
      <boxGeometry args={[0.9, 0.9, 0.9]} />
      <meshBasicMaterial color="#e5534b" transparent opacity={0.45} toneMapped={false} />
    </mesh>
  );
}

/** Where the user clicked: a ring that pulses outwards. */
export function TargetMarker({ grid, x, y }: { grid: Grid; x: number; y: number }) {
  const ring = useRef<Mesh>(null);
  const [wx, wz] = cellToWorld(grid, x, y);
  useFrame((state) => {
    if (!ring.current) return;
    const t = (state.clock.elapsedTime % 1.2) / 1.2;
    ring.current.scale.setScalar(0.6 + t * 0.9);
    (ring.current.material as { opacity: number }).opacity = 1 - t;
  });
  return (
    <mesh ref={ring} position={[wx, cellHeight(grid, x, y) + 0.12, wz]} rotation-x={-Math.PI / 2}>
      <ringGeometry args={[0.5, 0.62, 40]} />
      <meshBasicMaterial color="#58a6ff" transparent toneMapped={false} />
    </mesh>
  );
}
