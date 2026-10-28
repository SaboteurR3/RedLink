import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import {
  BufferAttribute,
  BufferGeometry,
  DataTexture,
  Euler,
  IcosahedronGeometry,
  InstancedMesh,
  LinearMipmapLinearFilter,
  Matrix4,
  Quaternion,
  RGBAFormat,
  RepeatWrapping,
  Vector3,
} from 'three';
import { heightAt, type Grid } from './geometry';
import { grainPixels, hash2, landscapeArrays, landscapeHeight } from './relief';
import { SURFACE_ROCK } from '../terrain/colorize';

/** The ground around the site out to the horizon: hills, craters, the crater rim and a layered peak. */
export function Landscape({ grid }: { grid: Grid }) {
  const geometry = useMemo(() => meshGeometry(landscapeArrays(grid)), [grid]);
  useEffect(() => () => geometry.dispose(), [geometry]);
  const grain = useGrainTexture();
  return (
    <mesh geometry={geometry} receiveShadow>
      <meshStandardMaterial vertexColors map={grain} bumpMap={grain} bumpScale={1.2} roughness={1} metalness={0} />
    </mesh>
  );
}

/**
 * Pebbles on the site's free cells and boulders on the plain around it. Decoration only: the rover
 * drives over pebbles, and only rock cells are obstacles.
 */
export function Pebbles({ grid }: { grid: Grid }) {
  const geometry = useRockGeometry();
  const mesh = useRef<InstancedMesh>(null);
  const stones = useMemo(() => scatter(grid), [grid]);

  useLayoutEffect(() => {
    const target = mesh.current;
    if (!target) return;
    const matrix = new Matrix4();
    stones.forEach((s, i) => {
      matrix.compose(
        new Vector3(s.x, s.y, s.z),
        new Quaternion().setFromEuler(new Euler(s.tilt, s.spin, s.tilt * 0.5)),
        new Vector3(s.size, s.size * 0.65, s.size * 0.9),
      );
      target.setMatrixAt(i, matrix);
    });
    target.instanceMatrix.needsUpdate = true;
  }, [stones]);

  return (
    <instancedMesh key={stones.length} ref={mesh} args={[geometry, undefined, stones.length]} receiveShadow>
      <meshStandardMaterial color="#4a3128" roughness={1} flatShading />
    </instancedMesh>
  );
}

/** A lumpy boulder shape shared by every rock (each instance turns and scales it differently). */
export function useRockGeometry(): BufferGeometry {
  const geometry = useMemo(() => {
    const g = new IcosahedronGeometry(1, 1);
    const p = g.getAttribute('position');
    for (let i = 0; i < p.count; i++) {
      const v = new Vector3(p.getX(i), p.getY(i), p.getZ(i));
      // The same corner of the shape always gets the same push, so shared vertices stay together.
      const push = 0.75 + 0.45 * hash2(Math.round(v.x * 100), Math.round(v.y * 100) + Math.round(v.z * 37));
      v.multiplyScalar(push);
      v.y = Math.max(v.y, -0.35); // a flat-ish underside that sits on the ground
      p.setXYZ(i, v.x, v.y, v.z);
    }
    g.computeVertexNormals();
    return g;
  }, []);
  useEffect(() => () => geometry.dispose(), [geometry]);
  return geometry;
}

/** The tiling grain texture (one tile per UV unit), used as colour and bump map. */
export function useGrainTexture(): DataTexture {
  const texture = useMemo(() => {
    const size = 256;
    const gray = grainPixels(size);
    const rgba = new Uint8Array(size * size * 4);
    gray.forEach((v, i) => rgba.set([v, v, v, 255], i * 4));
    const t = new DataTexture(rgba, size, size, RGBAFormat);
    t.wrapS = RepeatWrapping;
    t.wrapT = RepeatWrapping;
    t.minFilter = LinearMipmapLinearFilter;
    t.generateMipmaps = true;
    t.anisotropy = 4;
    t.needsUpdate = true;
    return t;
  }, []);
  useEffect(() => () => texture.dispose(), [texture]);
  return texture;
}

export function meshGeometry(arrays: { positions: Float32Array; colors: Float32Array; uvs: Float32Array; indices: Uint32Array }) {
  const g = new BufferGeometry();
  g.setAttribute('position', new BufferAttribute(arrays.positions, 3));
  g.setAttribute('color', new BufferAttribute(arrays.colors, 3));
  g.setAttribute('uv', new BufferAttribute(arrays.uvs, 2));
  g.setIndex(new BufferAttribute(arrays.indices, 1));
  g.computeVertexNormals();
  return g;
}

type Stone = {
  x: number;
  y: number;
  z: number;
  size: number;
  spin: number;
  tilt: number;
};

function scatter(grid: Grid): Stone[] {
  const stones: Stone[] = [];
  const m = grid.metersPerCell;
  // Pebbles: about one in three free cells, 5–15 cm.
  for (let cy = 0; cy < grid.height; cy++) {
    for (let cx = 0; cx < grid.width; cx++) {
      if (grid.surfaces[cy * grid.width + cx] === SURFACE_ROCK) continue;
      const r = hash2(cx * 7 + 3, cy * 13 + 1);
      if (r > 0.35) continue;
      const x = (cx + hash2(cx, cy + 99) - grid.width / 2) * m;
      const z = (cy + hash2(cx + 99, cy) - grid.height / 2) * m;
      const size = 0.05 + 0.1 * hash2(cx + 5, cy + 5);
      stones.push({
        x,
        y: heightAt(grid, x, z) + size * 0.1,
        z,
        size,
        spin: r * 40,
        tilt: r * 2,
      });
    }
  }
  // Boulders on the plain within 150 m: bigger the farther out.
  const half = (Math.max(grid.width, grid.height) * m) / 2;
  for (let i = 0; i < 400; i++) {
    const angle = hash2(i, 1) * Math.PI * 2;
    const distance = half + 3 + hash2(i, 2) ** 1.5 * 150;
    const x = Math.cos(angle) * distance;
    const z = Math.sin(angle) * distance;
    const size = 0.12 + hash2(i, 3) ** 4 * (0.5 + distance / 120);
    stones.push({
      x,
      y: landscapeHeight(grid, x, z) + size * 0.15,
      z,
      size,
      spin: hash2(i, 4) * 40,
      tilt: hash2(i, 5),
    });
  }
  return stones;
}
