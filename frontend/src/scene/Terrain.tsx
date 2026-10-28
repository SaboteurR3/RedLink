import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import { BufferAttribute, BufferGeometry, DoubleSide, Euler, InstancedMesh, Matrix4, Quaternion, Vector3 } from 'three';
import { cellHeight, cellToWorld, rockCells, terrainArrays, type Grid } from './geometry';

/** The landing site as a mesh: one vertex per cell centre, coloured like the 2D map. */
export function Terrain({ grid }: { grid: Grid }) {
  const geometry = useMemo(() => {
    const { positions, colors, indices } = terrainArrays(grid);
    const g = new BufferGeometry();
    g.setAttribute('position', new BufferAttribute(positions, 3));
    g.setAttribute('color', new BufferAttribute(colors, 3));
    g.setIndex(new BufferAttribute(indices, 1));
    g.computeVertexNormals();
    return g;
  }, [grid]);
  useEffect(() => () => geometry.dispose(), [geometry]);

  return (
    <mesh geometry={geometry} receiveShadow>
      <meshStandardMaterial vertexColors roughness={0.95} metalness={0} />
    </mesh>
  );
}

/** A boulder on every rock cell; size and tilt vary per cell but never between page loads. */
export function Rocks({ grid }: { grid: Grid }) {
  const cells = useMemo(() => rockCells(grid), [grid]);
  const mesh = useRef<InstancedMesh>(null);

  useLayoutEffect(() => {
    const target = mesh.current;
    if (!target) return;
    const matrix = new Matrix4();
    cells.forEach(([x, y], i) => {
      const [wx, wz] = cellToWorld(grid, x, y);
      const r1 = hash(x, y);
      const r2 = hash(y + 31, x + 7);
      const size = (0.38 + r1 * 0.22) * grid.metersPerCell;
      matrix.compose(
        new Vector3(wx, cellHeight(grid, x, y) + size * 0.35, wz),
        new Quaternion().setFromEuler(new Euler(r2 * 0.6, r1 * Math.PI * 2, r1 * 0.4)),
        new Vector3(size, size * (0.6 + r2 * 0.3), size),
      );
      target.setMatrixAt(i, matrix);
    });
    target.instanceMatrix.needsUpdate = true;
  }, [cells, grid]);

  return (
    <instancedMesh key={cells.length} ref={mesh} args={[undefined, undefined, cells.length]} castShadow receiveShadow>
      <dodecahedronGeometry args={[1, 0]} />
      <meshStandardMaterial color="#3d2d26" roughness={1} flatShading />
    </instancedMesh>
  );
}

/** The crater floor beyond the map and the wall rising around it, so the site has a horizon. */
export function CraterRim({ grid }: { grid: Grid }) {
  const radius = (Math.max(grid.width, grid.height) * grid.metersPerCell) / 2;
  return (
    <group>
      <mesh rotation-x={-Math.PI / 2} position-y={-0.05} receiveShadow>
        <ringGeometry args={[radius * 0.98, radius * 3, 64]} />
        <meshStandardMaterial color="#7a3f24" roughness={1} />
      </mesh>
      <mesh position-y={6}>
        <cylinderGeometry args={[radius * 3.4, radius * 3, 14, 64, 1, true]} />
        <meshStandardMaterial color="#8a4a2c" roughness={1} side={DoubleSide} />
      </mesh>
    </group>
  );
}

/** Deterministic value in [0, 1) per cell. */
function hash(x: number, y: number): number {
  let h = Math.imul(x, 374761393) + Math.imul(y, 668265263);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) & 0xffff) / 65536;
}
