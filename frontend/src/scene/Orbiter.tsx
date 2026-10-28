import { useFrame } from '@react-three/fiber';
import { useEffect, useRef } from 'react';
import type { Group, Mesh, MeshBasicMaterial } from 'three';
import type { LinkStatus } from '../api/types';
import { orbiterPosition, orbiterProgress } from './orbit';

/**
 * The relay orbiter that carries the uplink. With link windows on, it is above the horizon exactly
 * while a window is open, so the view shows why commands wait; otherwise it makes decorative passes.
 */
export function Orbiter({ link, half }: { link: LinkStatus | null; half: number }) {
  const group = useRef<Group>(null);
  const beacon = useRef<Mesh>(null);
  // The API says when the open window closes, not when it opened: remember the opening announced
  // while it was closed, or the first time this page saw it open.
  const opensAt = useRef<number | null>(null);
  const windowState = link?.window.enabled ? link.window : null;

  useEffect(() => {
    if (!windowState?.nextChangeAt) return;
    if (!windowState.open) opensAt.current = Date.parse(windowState.nextChangeAt);
    else if (opensAt.current === null || opensAt.current > Date.now()) opensAt.current = Date.now();
  }, [windowState?.open, windowState?.nextChangeAt]);

  useFrame(() => {
    const now = Date.now();
    let progress: number | null;
    if (windowState?.nextChangeAt) {
      const closesAt = Date.parse(windowState.nextChangeAt);
      const durationMs = Math.max(1000, closesAt - (opensAt.current ?? now));
      progress = orbiterProgress(now, { open: windowState.open, nextChangeAt: windowState.nextChangeAt, durationMs });
    } else {
      progress = orbiterProgress(now, null);
    }
    if (!group.current) return;
    group.current.visible = progress !== null;
    if (progress === null) return;
    group.current.position.set(...orbiterPosition(progress, half * 3.2));
    group.current.rotation.z = (progress - 0.5) * 0.6;
    if (beacon.current) (beacon.current.material as MeshBasicMaterial).opacity = Math.floor(now / 600) % 2 ? 1 : 0.25;
  });

  return (
    <group ref={group} visible={false}>
      <mesh>
        <boxGeometry args={[2, 1.4, 1.4]} />
        <meshStandardMaterial color="#c9ccd1" metalness={0.7} roughness={0.35} fog={false} />
      </mesh>
      {[-1, 1].map((side) => (
        <mesh key={side} position={[side * 3.4, 0, 0]}>
          <boxGeometry args={[4.4, 0.08, 1.6]} />
          <meshStandardMaterial color="#1d3b72" emissive="#0d2350" metalness={0.4} roughness={0.3} fog={false} />
        </mesh>
      ))}
      <mesh position={[0, -1, 0]} rotation-x={Math.PI}>
        <coneGeometry args={[0.8, 0.6, 16, 1, true]} />
        <meshStandardMaterial color="#e8e8e8" fog={false} />
      </mesh>
      <mesh ref={beacon} position={[0, 0.9, 0]}>
        <sphereGeometry args={[0.35, 12, 12]} />
        <meshBasicMaterial color="#7dff9a" transparent toneMapped={false} fog={false} />
      </mesh>
    </group>
  );
}
