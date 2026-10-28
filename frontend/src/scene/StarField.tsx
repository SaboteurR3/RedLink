import { useFrame } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import { AdditiveBlending, BufferAttribute, BufferGeometry, CanvasTexture, Color, type PointsMaterial } from 'three';
import { hash2 } from './relief';

/** Brightness classes: a few bright stars that glow, more medium ones, many faint ones. */
const CLASSES = [
  { count: 60, size: 9, brightness: 2.4 },
  { count: 400, size: 5, brightness: 1.3 },
  { count: 2600, size: 2.6, brightness: 0.8 },
];
const RADIUS = 800;
/** Star colours from hot blue-white to cool orange. */
const TINTS = ['#9fc3ff', '#d6e4ff', '#ffffff', '#fff1d6', '#ffd2a1'];

/**
 * The night sky: stars on a sphere behind the crater rim, in three brightness classes so the
 * brightest glow through the bloom, twinkling gently. `visibility` (0–1) fades them in at dusk.
 */
export function StarField({ visibility }: { visibility: { current: number } }) {
  const glow = useMemo(() => {
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 64;
    const g = canvas.getContext('2d');
    if (g) {
      const gradient = g.createRadialGradient(32, 32, 0, 32, 32, 32);
      gradient.addColorStop(0, 'rgba(255,255,255,1)');
      gradient.addColorStop(0.2, 'rgba(255,255,255,0.8)');
      gradient.addColorStop(0.5, 'rgba(255,255,255,0.15)');
      gradient.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = gradient;
      g.fillRect(0, 0, 64, 64);
    }
    return new CanvasTexture(canvas);
  }, []);
  useEffect(() => () => glow.dispose(), [glow]);

  const geometries = useMemo(
    () =>
      CLASSES.map((cls, c) => {
        const positions = new Float32Array(cls.count * 3);
        const colors = new Float32Array(cls.count * 3);
        const color = new Color();
        for (let i = 0; i < cls.count; i++) {
          // Uniform on the upper part of the sphere (a little below the horizon too, hidden by the rim).
          const u = hash2(i, c * 7 + 1);
          const v = hash2(i, c * 7 + 2);
          const theta = u * Math.PI * 2;
          const y = -0.1 + v * 1.1;
          const ring = Math.sqrt(1 - y * y);
          positions.set([Math.cos(theta) * ring * RADIUS, y * RADIUS, Math.sin(theta) * ring * RADIUS], i * 3);
          color.set(TINTS[Math.floor(hash2(i, c * 7 + 3) * TINTS.length)] ?? '#ffffff').multiplyScalar(cls.brightness);
          colors.set([color.r, color.g, color.b], i * 3);
        }
        const g = new BufferGeometry();
        g.setAttribute('position', new BufferAttribute(positions, 3));
        g.setAttribute('color', new BufferAttribute(colors, 3));
        return g;
      }),
    [],
  );
  useEffect(() => () => geometries.forEach((g) => g.dispose()), [geometries]);

  const materials = useRef<Array<PointsMaterial | null>>([]);
  useFrame((state) => {
    const t = state.clock.elapsedTime;
    materials.current.forEach((m, i) => {
      if (!m) return;
      // Each class twinkles at its own pace; the faint ones the most.
      const twinkle = 1 - (0.12 + i * 0.08) * (0.5 + 0.5 * Math.sin(t * (1.3 + i * 0.9) + i * 2));
      m.opacity = visibility.current * twinkle;
      m.visible = visibility.current > 0.01;
    });
  });

  return (
    <group>
      {CLASSES.map((cls, i) => (
        <points key={i} geometry={geometries[i]} frustumCulled={false}>
          <pointsMaterial
            ref={(m) => {
              materials.current[i] = m;
            }}
            map={glow}
            size={cls.size}
            sizeAttenuation={false}
            vertexColors
            transparent
            opacity={0}
            depthWrite={false}
            blending={AdditiveBlending}
            toneMapped={false}
            fog={false}
          />
        </points>
      ))}
    </group>
  );
}
