import { Sparkles } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef, type RefObject } from 'react';
import type { Group, Mesh, MeshBasicMaterial, MeshStandardMaterial, PointLight, SpotLight } from 'three';
import type { Heading, RoverMode } from '../api/types';
import { cellToWorld, headingToYaw, heightAt, shortestTurn, type Grid } from './geometry';
import { daylight, scenePhase } from './sol';

/** What the rover is busy with, from the command it is executing (as last reported). */
export type RoverActivity = 'move' | 'rotate' | 'photo' | 'drill' | 'charge' | null;

const WHEEL_RADIUS = 0.17;
const WHEELS: Array<[number, number]> = [
  [-0.5, -0.45],
  [0.5, -0.45],
  [-0.55, 0],
  [0.55, 0],
  [-0.5, 0.45],
  [0.5, 0.45],
];

type BodyRefs = {
  wheels?: RefObject<(Group | null)[]>;
  mastHead?: RefObject<Group | null>;
  drillArm?: RefObject<Group | null>;
};

/**
 * A small six-wheeled rover built from primitives, facing −Z (north) at yaw 0, about one grid cell
 * long. `ghost` makes it translucent (the mission forecast). The refs expose the moving parts.
 */
export function RoverBody({ ghost = false, parts = {} }: { ghost?: boolean; parts?: BodyRefs }) {
  const look = ghost ? { transparent: true, opacity: 0.35, depthWrite: false } : {};
  const color = (c: string) => (ghost ? '#7fb8ff' : c);
  return (
    <group>
      {WHEELS.map(([x, z], i) => (
        <group
          key={`${x},${z}`}
          position={[x, WHEEL_RADIUS, z]}
          ref={(g) => {
            if (parts.wheels?.current) parts.wheels.current[i] = g;
          }}
        >
          <mesh rotation-z={Math.PI / 2} castShadow={!ghost}>
            <cylinderGeometry args={[WHEEL_RADIUS, WHEEL_RADIUS, 0.14, 16]} />
            <meshStandardMaterial color={color('#2b2b2e')} roughness={0.8} {...look} />
          </mesh>
          {/* a bright spoke on the outside, so turning wheels can be seen */}
          <mesh position={[x < 0 ? -0.075 : 0.075, 0, 0]}>
            <boxGeometry args={[0.01, WHEEL_RADIUS * 1.7, 0.035]} />
            <meshStandardMaterial color={color('#c9c3b5')} {...look} />
          </mesh>
        </group>
      ))}
      <mesh position={[0, 0.5, 0]} castShadow={!ghost}>
        <boxGeometry args={[0.85, 0.3, 1.15]} />
        <meshStandardMaterial color={color('#d9d4c7')} roughness={0.6} metalness={0.2} {...look} />
      </mesh>
      <mesh position={[0, 0.67, 0.08]} castShadow={!ghost}>
        <boxGeometry args={[0.95, 0.04, 0.9]} />
        <meshStandardMaterial color={color('#23324f')} roughness={0.35} metalness={0.6} {...look} />
      </mesh>
      <mesh position={[0.28, 1.0, -0.42]} castShadow={!ghost}>
        <cylinderGeometry args={[0.04, 0.05, 0.7, 10]} />
        <meshStandardMaterial color={color('#cfc9bb')} {...look} />
      </mesh>
      <group position={[0.28, 1.38, -0.45]} ref={parts.mastHead}>
        <mesh castShadow={!ghost}>
          <boxGeometry args={[0.3, 0.14, 0.14]} />
          <meshStandardMaterial color={color('#e8e2d4')} {...look} />
        </mesh>
        {!ghost && (
          <mesh position={[0, 0, -0.075]}>
            <circleGeometry args={[0.035, 16]} />
            <meshBasicMaterial color="#1b2a3d" />
          </mesh>
        )}
      </group>
      <mesh position={[-0.28, 0.82, 0.3]} rotation-x={-0.6}>
        <cylinderGeometry args={[0.16, 0.02, 0.06, 20]} />
        <meshStandardMaterial color={color('#bfb9aa')} metalness={0.4} {...look} />
      </mesh>
      {!ghost && (
        // The drill arm, folded up under the front until a sample is taken.
        <group position={[-0.2, 0.42, -0.6]} ref={parts.drillArm}>
          <mesh position={[0, -0.18, 0]}>
            <boxGeometry args={[0.07, 0.36, 0.07]} />
            <meshStandardMaterial color="#9b968a" metalness={0.5} />
          </mesh>
          <mesh position={[0, -0.4, 0]}>
            <coneGeometry args={[0.045, 0.12, 10]} />
            <meshStandardMaterial color="#6d6a63" metalness={0.7} />
          </mesh>
        </group>
      )}
    </group>
  );
}

/**
 * The rover where Earth last heard it was. On a new report it glides to the new cell and turns the
 * short way round, so the scene never jumps; it never moves without a report. Its parts animate
 * for what it is doing: wheels turn as it moves, the mast pans and flashes for photos, the drill
 * arm lowers for samples, and the headlights come on at night.
 *
 * @param flashAt time (ms) of the latest photo, for the camera flash
 */
export function Rover({
  grid,
  x,
  y,
  heading,
  mode,
  activity = null,
  flashAt = 0,
  groupRef,
}: {
  grid: Grid;
  x: number;
  y: number;
  heading: Heading;
  mode: RoverMode;
  activity?: RoverActivity;
  flashAt?: number;
  groupRef: RefObject<Group | null>;
}) {
  const target = useMemo(() => {
    const [wx, wz] = cellToWorld(grid, x, y);
    return { x: wx, z: wz, yaw: headingToYaw(heading) };
  }, [grid, x, y, heading]);
  const placed = useRef(false);
  const beacon = useRef<Mesh>(null);
  const beaconLight = useRef<PointLight>(null);
  const wheels = useRef<(Group | null)[]>([]);
  const mastHead = useRef<Group>(null);
  const drillArm = useRef<Group>(null);
  const flash = useRef<Mesh>(null);
  const flashLight = useRef<PointLight>(null);
  const lights = useRef<SpotLight[]>([]);
  const dust = useRef<Group>(null);

  useLayoutEffect(() => {
    const g = groupRef.current;
    if (!g || placed.current) return;
    g.position.set(target.x, heightAt(grid, target.x, target.z), target.z);
    g.rotation.y = target.yaw;
    placed.current = true;
  }, [grid, target, groupRef]);

  // Headlights point ahead of the rover.
  useLayoutEffect(() => {
    for (const light of lights.current) light.target.position.set(light.position.x, 0, -6);
  }, []);

  useFrame((state, delta) => {
    const g = groupRef.current;
    if (!g) return;
    const k = 1 - Math.exp(-delta * 3);
    const beforeX = g.position.x;
    const beforeZ = g.position.z;
    g.position.x += (target.x - g.position.x) * k;
    g.position.z += (target.z - g.position.z) * k;
    g.position.y = heightAt(grid, g.position.x, g.position.z);
    const turn = shortestTurn(g.rotation.y, target.yaw) * k;
    g.rotation.y += turn;

    // Wheels: rolling distance driven, plus a little for turning on the spot.
    const rolled = Math.hypot(g.position.x - beforeX, g.position.z - beforeZ) + Math.abs(turn) * 0.5;
    for (const wheel of wheels.current) if (wheel) wheel.rotation.x -= rolled / WHEEL_RADIUS;

    const t = state.clock.elapsedTime;
    if (mastHead.current) {
      const pan = activity === 'photo' ? Math.sin(t * 2.2) * 0.7 : 0;
      mastHead.current.rotation.y += (pan - mastHead.current.rotation.y) * Math.min(1, delta * 4);
    }
    if (drillArm.current) {
      const down = activity === 'drill' ? 1 : 0;
      drillArm.current.position.y += (0.42 - down * 0.18 - drillArm.current.position.y) * Math.min(1, delta * 3);
      drillArm.current.rotation.y += down * delta * 12; // the bit spins
    }
    if (dust.current) dust.current.visible = activity === 'drill';

    const sinceFlash = Date.now() - flashAt;
    const flashOn = flashAt > 0 && sinceFlash < 450 ? 1 - sinceFlash / 450 : 0;
    if (flash.current) {
      flash.current.visible = flashOn > 0;
      flash.current.scale.setScalar(0.5 + flashOn);
      (flash.current.material as MeshBasicMaterial).opacity = flashOn;
    }
    if (flashLight.current) flashLight.current.intensity = flashOn * 25;

    const night = 1 - daylight(scenePhase());
    for (const light of lights.current) light.intensity = night > 0.3 ? 14 * night : 0;

    if (beacon.current && beaconLight.current) {
      const pulse = 0.5 + 0.5 * Math.sin(t * 6);
      (beacon.current.material as MeshStandardMaterial).emissiveIntensity = 1 + pulse * 3;
      beaconLight.current.intensity = 2 + pulse * 6;
    }
  });

  return (
    <group ref={groupRef}>
      <RoverBody parts={{ wheels, mastHead, drillArm }} />
      {[-0.3, 0.3].map((side, i) => (
        <spotLight
          key={side}
          ref={(l) => {
            if (l) lights.current[i] = l;
          }}
          position={[side, 0.55, -0.6]}
          angle={0.5}
          penumbra={0.6}
          distance={14}
          intensity={0}
          color="#fff3dd"
        />
      ))}
      {[-0.3, 0.3].map((side) => (
        <mesh key={`lamp${side}`} position={[side, 0.55, -0.58]}>
          <circleGeometry args={[0.05, 12]} />
          <meshBasicMaterial color="#fff3dd" toneMapped={false} />
        </mesh>
      ))}
      <mesh ref={flash} position={[0.28, 1.38, -0.56]} visible={false}>
        <sphereGeometry args={[0.18, 16, 16]} />
        <meshBasicMaterial color="#ffffff" transparent toneMapped={false} />
      </mesh>
      <pointLight ref={flashLight} position={[0.28, 1.38, -0.7]} intensity={0} distance={10} color="#ffffff" />
      <group ref={dust} position={[-0.2, 0.1, -0.62]} visible={false}>
        <Sparkles count={60} scale={[0.8, 0.5, 0.8]} size={5} speed={2.5} color="#d9a77c" opacity={0.9} />
      </group>
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
