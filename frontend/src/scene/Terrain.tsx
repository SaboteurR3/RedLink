import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import { Euler, InstancedMesh, Matrix4, Quaternion, Vector3 } from 'three';
import { cellHeight, cellToWorld, rockCells, worldToCell, type Grid } from './geometry';
import { meshGeometry, useGrainTexture, useRockGeometry } from './Landscape';
import { hash2 as hash, siteArrays } from './relief';

/**
 * The landing site as a mesh: several vertices per cell on the surface the rover drives on, coloured
 * like the 2D map, with grain up close. `onCellClick` gets the clicked cell (a drag of the camera is
 * not a click).
 */
export function Terrain({ grid, onCellClick }: { grid: Grid; onCellClick?: (x: number, y: number) => void }) {
  const geometry = useMemo(() => meshGeometry(siteArrays(grid)), [grid]);
  useEffect(() => () => geometry.dispose(), [geometry]);
  const grain = useGrainTexture();

  return (
    <mesh
      geometry={geometry}
      receiveShadow
      onClick={(event) => {
        if (!onCellClick || event.delta > 4) return; // the user was rotating the camera
        event.stopPropagation();
        const cell = worldToCell(grid, event.point.x, event.point.z);
        if (cell) onCellClick(cell.x, cell.y);
      }}
      onPointerOver={() => onCellClick && (document.body.style.cursor = 'crosshair')}
      onPointerOut={() => (document.body.style.cursor = '')}
    >
      <meshStandardMaterial vertexColors map={grain} bumpMap={grain} bumpScale={0.8} roughness={0.95} metalness={0} />
    </mesh>
  );
}

/** A boulder on every rock cell; size and tilt vary per cell but never between page loads. */
export function Rocks({ grid }: { grid: Grid }) {
  const cells = useMemo(() => rockCells(grid), [grid]);
  const mesh = useRef<InstancedMesh>(null);
  const geometry = useRockGeometry();

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
        new Vector3(wx, cellHeight(grid, x, y) + size * 0.15, wz),
        new Quaternion().setFromEuler(new Euler(r2 * 0.6, r1 * Math.PI * 2, r1 * 0.4)),
        new Vector3(size, size * (0.6 + r2 * 0.3), size),
      );
      target.setMatrixAt(i, matrix);
    });
    target.instanceMatrix.needsUpdate = true;
  }, [cells, grid]);

  return (
    <instancedMesh key={cells.length} ref={mesh} args={[geometry, undefined, cells.length]} castShadow receiveShadow>
      <meshStandardMaterial color="#3d2d26" roughness={1} flatShading />
    </instancedMesh>
  );
}
