import type { Heading, Sample } from '../api/types';
import { cellHeight, cellToWorld, headingToYaw, heightAt, type Grid } from './geometry';
import { hash2 } from './relief';

/** World direction (x, z) one step in `heading`; north is −z. */
const AHEAD: Record<Heading, [number, number]> = { NORTH: [0, -1], EAST: [1, 0], SOUTH: [0, 1], WEST: [-1, 0] };

/**
 * Where each drill sample was taken. Soil: a dark hole with pale tailings just in front of where
 * the rover stood. Rock: a pale scar on the side of the rock it faced, with drill dust at its foot.
 * A small yellow flag marks each one so it can be found from afar. The rocks themselves stay.
 */
export function DrillMarks({ grid, samples }: { grid: Grid; samples: Sample[] }) {
  return (
    <group>
      {samples.map((s) =>
        s.material === 'ROCK' ? (
          <RockScar key={s.commandId} grid={grid} sample={s} />
        ) : (
          <SoilHole key={s.commandId} grid={grid} sample={s} />
        ),
      )}
    </group>
  );
}

function SoilHole({ grid, sample }: { grid: Grid; sample: Sample }) {
  const [dx, dz] = AHEAD[sample.heading];
  const [cx, cz] = cellToWorld(grid, sample.x, sample.y);
  // Just past the rover's front wheels, where the arm reaches.
  const x = cx + dx * 0.6 * grid.metersPerCell;
  const z = cz + dz * 0.6 * grid.metersPerCell;
  const y = heightAt(grid, x, z) + 0.015;
  return (
    <group position={[x, y, z]}>
      <mesh rotation-x={-Math.PI / 2}>
        <ringGeometry args={[0.1, 0.32, 28]} />
        <meshStandardMaterial color="#c9a27c" roughness={1} transparent opacity={0.85} />
      </mesh>
      <mesh rotation-x={-Math.PI / 2} position-y={0.002}>
        <circleGeometry args={[0.1, 20]} />
        <meshBasicMaterial color="#1a0d08" />
      </mesh>
      <Flag x={-dz * 0.25} z={dx * 0.25} />
    </group>
  );
}

function RockScar({ grid, sample }: { grid: Grid; sample: Sample }) {
  const [dx, dz] = AHEAD[sample.heading];
  // Rows run north to south along +z, so the rock's cell is one step along the same direction.
  const rockX = sample.x + dx;
  const rockY = sample.y + dz;
  const [rx, rz] = cellToWorld(grid, rockX, rockY);
  // The same size the boulder on this cell is drawn with (see Rocks).
  const size = (0.38 + hash2(rockX, rockY) * 0.22) * grid.metersPerCell;
  const base = cellHeight(grid, rockX, rockY);
  // On the rock's side facing the rover, a little above the ground.
  const scarX = rx - dx * size * 0.85;
  const scarZ = rz - dz * size * 0.85;
  const dustX = rx - dx * size * 1.35;
  const dustZ = rz - dz * size * 1.35;
  return (
    <group>
      <group position={[scarX, base + size * 0.3, scarZ]} rotation-y={headingToYaw(sample.heading)}>
        {/* The drilled patch: brushed pale rock with the dark core hole in the middle. */}
        <mesh position-z={0.02}>
          <circleGeometry args={[0.22, 24]} />
          <meshStandardMaterial color="#d8c2a8" roughness={0.9} emissive="#3a2a1e" />
        </mesh>
        <mesh position-z={0.025}>
          <circleGeometry args={[0.07, 16]} />
          <meshBasicMaterial color="#120a07" />
        </mesh>
      </group>
      <mesh position={[dustX, heightAt(grid, dustX, dustZ) + 0.01, dustZ]} rotation-x={-Math.PI / 2}>
        <circleGeometry args={[0.32, 24]} />
        <meshStandardMaterial color="#c9a27c" roughness={1} transparent opacity={0.8} />
      </mesh>
      <group position={[dustX, heightAt(grid, dustX, dustZ), dustZ]}>
        <Flag x={-dz * 0.3} z={dx * 0.3} />
      </group>
    </group>
  );
}

/** A thin post with a small yellow flag, beside a sample. */
function Flag({ x, z }: { x: number; z: number }) {
  return (
    <group position={[x, 0, z]}>
      <mesh position-y={0.4}>
        <cylinderGeometry args={[0.015, 0.015, 0.8, 6]} />
        <meshStandardMaterial color="#d9d4cc" />
      </mesh>
      <mesh position={[0.1, 0.72, 0]}>
        <boxGeometry args={[0.2, 0.13, 0.008]} />
        <meshStandardMaterial color="#f2c14e" emissive="#5a4210" />
      </mesh>
    </group>
  );
}
