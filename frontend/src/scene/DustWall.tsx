import { useFrame } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import { CanvasTexture, DoubleSide, RepeatWrapping, type MeshBasicMaterial } from 'three';

const LAYERS = [
  { radius: 1.05, speed: 0.012, opacity: 0.85 },
  { radius: 0.8, speed: 0.02, opacity: 0.4 },
];

/**
 * A storm front: rings of streaky dust around the crater that fade in while a dust storm is on
 * and drift with the wind. The texture is drawn once on a canvas (no image to download).
 */
export function DustWall({ half, active }: { half: number; active: boolean }) {
  const textures = useMemo(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 512;
    canvas.height = 128;
    const g = canvas.getContext('2d');
    if (g) {
      const fade = g.createLinearGradient(0, 0, 0, 128);
      fade.addColorStop(0, 'rgba(160,110,70,0)');
      fade.addColorStop(0.35, 'rgba(160,110,70,0.7)');
      fade.addColorStop(1, 'rgba(120,80,50,1)');
      g.fillStyle = fade;
      g.fillRect(0, 0, 512, 128);
      // Wind streaks: thin, long, lighter strokes.
      for (let i = 0; i < 140; i++) {
        const y = 20 + ((i * 53) % 108);
        const x = (i * 197) % 512;
        g.strokeStyle = `rgba(233,195,156,${0.15 + ((i * 7) % 10) / 30})`;
        g.lineWidth = 1 + (i % 3);
        g.beginPath();
        g.moveTo(x, y);
        g.lineTo(x + 40 + ((i * 31) % 120), y - 3);
        g.stroke();
      }
    }
    const t = new CanvasTexture(canvas);
    t.wrapS = RepeatWrapping;
    t.repeat.set(4, 1);
    const inner = t.clone();
    inner.needsUpdate = true;
    return [t, inner];
  }, []);
  useEffect(() => () => textures.forEach((t) => t.dispose()), [textures]);

  const materials = useRef<Array<MeshBasicMaterial | null>>([]);
  const strength = useRef(0);
  useFrame((_, delta) => {
    // Ease in and out over about two seconds.
    strength.current += ((active ? 1 : 0) - strength.current) * Math.min(1, delta * 1.5);
    LAYERS.forEach((layer, i) => {
      const m = materials.current[i];
      const texture = textures[i];
      if (!m || !texture) return;
      m.opacity = layer.opacity * strength.current;
      m.visible = strength.current > 0.01;
      texture.offset.x -= delta * layer.speed;
    });
  });

  return (
    <>
      {LAYERS.map((layer, i) => (
        <mesh key={i} position={[0, half * 0.3, 0]}>
          <cylinderGeometry args={[half * layer.radius, half * layer.radius * 1.15, half * 0.8, 64, 1, true]} />
          <meshBasicMaterial
            ref={(m) => {
              materials.current[i] = m;
            }}
            map={textures[i]}
            transparent
            opacity={0}
            side={DoubleSide}
            depthWrite={false}
            fog={false}
          />
        </mesh>
      ))}
    </>
  );
}
