import { Line } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef, type ComponentRef } from 'react';
import { QuadraticBezierCurve3, Vector3, type Group, type Mesh, type MeshBasicMaterial } from 'three';
import type { Heading, Photo, UplinkTransmission } from '../api/types';
import { beamProgress, cellHeight, cellToWorld, headingToYaw, type Grid } from './geometry';
import { RoverBody } from './RoverModel';

/** Where the signal comes from: high in the sky, towards Earth. */
const EARTH = new Vector3(-70, 110, -140);
/** How long an arrived beam stays visible while it fades. */
const FADE_MS = 1500;

/**
 * Commands on their way to Mars. Each pulse travels on the real clock: it leaves at
 * `transmittedAt` and reaches the rover exactly at `arrivesAt`, when the packet does.
 */
export function Beams({ uplinks, target }: { uplinks: UplinkTransmission[]; target: Vector3 }) {
  return (
    <>
      {uplinks.map((uplink) => (
        <Beam key={`${uplink.commandId}-${uplink.sequenceNo}`} uplink={uplink} target={target} />
      ))}
    </>
  );
}

function Beam({ uplink, target }: { uplink: UplinkTransmission; target: Vector3 }) {
  const curve = useMemo(() => {
    const control = new Vector3().addVectors(EARTH, target).multiplyScalar(0.5).add(new Vector3(0, 40, 0));
    return new QuadraticBezierCurve3(EARTH.clone(), control, target.clone());
  }, [target]);
  const points = useMemo(() => curve.getPoints(64), [curve]);
  const pulse = useRef<Mesh>(null);
  const group = useRef<Group>(null);
  const line = useRef<ComponentRef<typeof Line>>(null);

  useFrame(() => {
    const now = Date.now();
    const progress = beamProgress(uplink.transmittedAt, uplink.arrivesAt, now);
    const fade = 1 - Math.min(1, Math.max(0, now - Date.parse(uplink.arrivesAt)) / FADE_MS);
    if (group.current) group.current.visible = fade > 0;
    if (pulse.current) {
      pulse.current.position.copy(curve.getPoint(progress));
      pulse.current.scale.setScalar(progress >= 1 ? 1 + (1 - fade) * 4 : 1);
      (pulse.current.material as MeshBasicMaterial).opacity = fade;
    }
    if (line.current) line.current.material.opacity = 0.45 * fade;
  });

  return (
    <group ref={group}>
      <Line ref={line} points={points} color="#7fd4ff" lineWidth={1.5} transparent opacity={0.45} dashed={false} />
      <mesh ref={pulse}>
        <sphereGeometry args={[0.6, 16, 16]} />
        <meshBasicMaterial color="#bfeaff" transparent toneMapped={false} />
      </mesh>
    </group>
  );
}

/** A small post with a camera head where each photo was taken, looking the way the camera looked. */
export function PhotoPins({ grid, photos }: { grid: Grid; photos: Photo[] }) {
  return (
    <>
      {photos.map((photo) => {
        const [wx, wz] = cellToWorld(grid, photo.x, photo.y);
        return (
          <group key={photo.id} position={[wx, cellHeight(grid, photo.x, photo.y), wz]} rotation-y={headingToYaw(photo.heading as Heading)}>
            <mesh position={[0.3, 0.3, 0.3]}>
              <cylinderGeometry args={[0.015, 0.015, 0.6, 6]} />
              <meshStandardMaterial color="#58a6ff" />
            </mesh>
            <mesh position={[0.3, 0.63, 0.27]}>
              <boxGeometry args={[0.09, 0.07, 0.12]} />
              <meshStandardMaterial color="#58a6ff" emissive="#1f5fa8" />
            </mesh>
          </group>
        );
      })}
    </>
  );
}

/** Where Earth forecasts the rover will stand when the planned mission is done. */
export function Ghost({ grid, x, y, heading }: { grid: Grid; x: number; y: number; heading: Heading }) {
  const [wx, wz] = cellToWorld(grid, x, y);
  return (
    <group position={[wx, cellHeight(grid, x, y), wz]} rotation-y={headingToYaw(heading)}>
      <RoverBody ghost />
    </group>
  );
}
