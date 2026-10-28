import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef, type RefObject } from 'react';
import type { Group, Mesh, MeshStandardMaterial, PointLight } from 'three';
import type { Heading, RoverMode } from '../api/types';
import { cellToWorld, headingToYaw, heightAt, shortestTurn, type Grid } from './geometry';

const WHEELS: Array<[number, number]> = [
  [-0.5, -0.45],
  [0.5, -0.45],
  [-0.55, 0],
  [0.55, 0],
  [-0.5, 0.45],
  [0.5, 0.45],
];

/**
 * A small six-wheeled rover built from primitives, facing −Z (north) at yaw 0, about one grid cell
 * long. `ghost` makes it translucent (the mission forecast).
 */
export function RoverBody({ ghost = false }: { ghost?: boolean }) {
  const look = ghost ? { transparent: true, opacity: 0.35, depthWrite: false } : {};
  const color = (c: string) => (ghost ? '#7fb8ff' : c);
  return (
    <group>
      {WHEELS.map(([x, z]) => (
        <mesh key={`${x},${z}`} position={[x, 0.17, z]} rotation-z={Math.PI / 2} castShadow={!ghost}>
          <cylinderGeometry args={[0.17, 0.17, 0.14, 16]} />
          <meshStandardMaterial color={color('#2b2b2e')} roughness={0.8} {...look} />
        </mesh>
      ))}
      {/* body */}
      <mesh position={[0, 0.5, 0]} castShadow={!ghost}>
        <boxGeometry args={[0.85, 0.3, 1.15]} />
        <meshStandardMaterial color={color('#d9d4c7')} roughness={0.6} metalness={0.2} {...look} />
      </mesh>
      {/* solar deck */}
      <mesh position={[0, 0.67, 0.08]} castShadow={!ghost}>
        <boxGeometry args={[0.95, 0.04, 0.9]} />
        <meshStandardMaterial color={color('#23324f')} roughness={0.35} metalness={0.6} {...look} />
      </mesh>
      {/* mast with camera head, at the front right */}
      <mesh position={[0.28, 1.0, -0.42]} castShadow={!ghost}>
        <cylinderGeometry args={[0.04, 0.05, 0.7, 10]} />
        <meshStandardMaterial color={color('#cfc9bb')} {...look} />
      </mesh>
      <mesh position={[0.28, 1.38, -0.45]} castShadow={!ghost}>
        <boxGeometry args={[0.3, 0.14, 0.14]} />
        <meshStandardMaterial color={color('#e8e2d4')} {...look} />
      </mesh>
      {/* high-gain antenna */}
      <mesh position={[-0.28, 0.82, 0.3]} rotation-x={-0.6}>
        <cylinderGeometry args={[0.16, 0.02, 0.06, 20]} />
        <meshStandardMaterial color={color('#bfb9aa')} metalness={0.4} {...look} />
      </mesh>
    </group>
  );
}

/**
 * The rover where Earth last heard it was. On a new report it glides to the new cell and turns the
 * short way round, so the scene never jumps; it never moves without a report.
 */
export function Rover({
  grid,
  x,
  y,
  heading,
  mode,
  groupRef,
}: {
  grid: Grid;
  x: number;
  y: number;
  heading: Heading;
  mode: RoverMode;
  groupRef: RefObject<Group | null>;
}) {
  const target = useMemo(() => {
    const [wx, wz] = cellToWorld(grid, x, y);
    return { x: wx, z: wz, yaw: headingToYaw(heading) };
  }, [grid, x, y, heading]);
  const placed = useRef(false);
  const beacon = useRef<Mesh>(null);
  const beaconLight = useRef<PointLight>(null);

  // First report: stand there at once instead of gliding in from the origin.
  useLayoutEffect(() => {
    const g = groupRef.current;
    if (!g || placed.current) return;
    g.position.set(target.x, heightAt(grid, target.x, target.z), target.z);
    g.rotation.y = target.yaw;
    placed.current = true;
  }, [grid, target, groupRef]);

  useFrame((state, delta) => {
    const g = groupRef.current;
    if (!g) return;
    const k = 1 - Math.exp(-delta * 3);
    g.position.x += (target.x - g.position.x) * k;
    g.position.z += (target.z - g.position.z) * k;
    g.position.y = heightAt(grid, g.position.x, g.position.z);
    g.rotation.y += shortestTurn(g.rotation.y, target.yaw) * k;

    if (beacon.current && beaconLight.current) {
      const pulse = 0.5 + 0.5 * Math.sin(state.clock.elapsedTime * 6);
      (beacon.current.material as MeshStandardMaterial).emissiveIntensity = 1 + pulse * 3;
      beaconLight.current.intensity = 2 + pulse * 6;
    }
  });

  return (
    <group ref={groupRef}>
      <RoverBody />
      {mode === 'SAFE_MODE' && (
        <group position={[0, 1.6, 0]}>
          <mesh ref={beacon}>
            <sphereGeometry args={[0.12, 16, 16]} />
            <meshStandardMaterial color="#ff3b30" emissive="#ff3b30" emissiveIntensity={2} />
          </mesh>
          <pointLight ref={beaconLight} color="#ff3b30" distance={8} intensity={4} />
        </group>
      )}
    </group>
  );
}
