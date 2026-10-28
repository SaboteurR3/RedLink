import { Line } from '@react-three/drei';
import { Canvas, useFrame } from '@react-three/fiber';
import { Bloom, EffectComposer, Vignette } from '@react-three/postprocessing';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AdditiveBlending, BackSide, CanvasTexture, Color, SRGBColorSpace, Vector3, type Mesh } from 'three';
import { introStage } from './orbit';
import { fbm, hash2 } from './relief';
import { StarField } from './StarField';

const EARTH_AT = new Vector3(0, 0, 0);
const MARS_AT = new Vector3(14, -2, -160);
/** The sun is off to the side, so both planets show a lit crescent and a dark night side. */
const SUN_AT = new Vector3(420, 60, -120);
const CAPTIONS = {
  earth: 'Earth · Mission Control',
  flight: 'Every command crosses the gap at the speed of light',
  mars: 'Mars · the landing site',
  fade: 'Mars · the landing site',
  done: '',
} as const;

/**
 * The opening shot after login: from Earth, alongside a command's radio pulse across space, down to
 * Mars, then a fade into the site. Plays once per browser tab session and can be skipped. Default
 * export, lazy-loaded with three.js.
 */
export default function Intro({ onDone }: { onDone: () => void }) {
  const [start] = useState(() => performance.now());
  const [stage, setStage] = useState(introStage(0));
  const starsOn = useRef(1);

  useEffect(() => {
    const timer = setInterval(() => {
      const now = introStage(performance.now() - start);
      setStage(now);
      if (now.stage === 'done') onDone();
    }, 100);
    const skip = (event: KeyboardEvent) => event.key === 'Escape' && onDone();
    window.addEventListener('keydown', skip);
    return () => {
      clearInterval(timer);
      window.removeEventListener('keydown', skip);
    };
  }, [start, onDone]);

  return (
    <div className="intro" style={{ opacity: stage.stage === 'fade' ? 1 - stage.t : 1 }} role="dialog" aria-label="Intro">
      <Canvas camera={{ position: [0, 0.6, 6], fov: 45, near: 0.1, far: 2000 }}>
        <color attach="background" args={['#020308']} />
        <ambientLight intensity={0.08} />
        <directionalLight position={SUN_AT} intensity={4} />
        <StarField visibility={starsOn} />
        <Sun />
        <Planet at={EARTH_AT} radius={1.6} kind="earth" spin={0.05} />
        <Planet at={MARS_AT} radius={5} kind="mars" spin={0.02} />
        <Signal start={start} />
        <Flight start={start} />
        <EffectComposer multisampling={0}>
          <Bloom intensity={1.2} luminanceThreshold={0.6} luminanceSmoothing={0.3} mipmapBlur />
          <Vignette offset={0.25} darkness={0.7} />
        </EffectComposer>
      </Canvas>
      <div className="intro-bars" aria-hidden="true" />
      {stage.stage === 'earth' && (
        <h1 className="intro-title" aria-hidden="true">
          RedLink
        </h1>
      )}
      <p className="intro-caption" key={stage.stage}>
        {CAPTIONS[stage.stage]}
      </p>
      <button className="secondary intro-skip" onClick={onDone}>
        Skip ▸
      </button>
    </div>
  );
}

/** Moves the camera along the timeline: around Earth, across beside the pulse, down to Mars. */
function Flight({ start }: { start: number }) {
  const look = useMemo(() => new Vector3(), []);
  useFrame(({ camera }) => {
    const { stage, t } = introStage(performance.now() - start);
    const ease = t * t * (3 - 2 * t);
    if (stage === 'earth') {
      camera.position.set(Math.sin(t * 0.6) * 6, 0.6 + t * 0.3, Math.cos(t * 0.6) * 6);
      look.copy(EARTH_AT);
    } else if (stage === 'flight') {
      const from = new Vector3(Math.sin(0.6) * 6, 0.9, Math.cos(0.6) * 6);
      const to = MARS_AT.clone().add(new Vector3(-6, 4, 38));
      camera.position.lerpVectors(from, to, ease);
      look.lerpVectors(EARTH_AT, MARS_AT, Math.min(1, ease * 1.6));
    } else {
      // Down towards the lit side of Mars until it fills the view.
      const from = MARS_AT.clone().add(new Vector3(-6, 4, 38));
      const to = MARS_AT.clone().add(new Vector3(15, 3.6, -2.4));
      camera.position.lerpVectors(from, to, stage === 'mars' ? ease : 1);
      look.copy(MARS_AT).add(new Vector3(1.5, 0, 0));
    }
    camera.lookAt(look);
  });
  return null;
}

/** A command's radio pulse racing from Earth to Mars ahead of the camera, with a fading trail. */
function Signal({ start }: { start: number }) {
  const head = useRef<Mesh>(null);
  const [trail, setTrail] = useState<[Vector3, Vector3]>([EARTH_AT.clone(), EARTH_AT.clone()]);
  useFrame((state) => {
    const { stage, t } = introStage(performance.now() - start);
    const progress = stage === 'earth' ? 0 : stage === 'flight' ? Math.min(1, t * 1.15) : 1;
    const at = new Vector3().lerpVectors(EARTH_AT, MARS_AT, progress);
    if (head.current) {
      head.current.position.copy(at);
      head.current.visible = progress > 0 && progress < 1;
      head.current.scale.setScalar(1 + 0.25 * Math.sin(state.clock.elapsedTime * 20));
    }
    // The trail follows the pulse and shrinks into Mars once it has arrived.
    const tail = new Vector3().lerpVectors(EARTH_AT, MARS_AT, progress < 1 ? Math.max(0, progress - 0.25) : 1);
    if (!trail[1].equals(at) || !trail[0].equals(tail)) setTrail([tail, at]);
  });
  return (
    <>
      <mesh ref={head} visible={false}>
        <sphereGeometry args={[0.35, 16, 16]} />
        <meshBasicMaterial color={new Color('#9fd4ff').multiplyScalar(4)} toneMapped={false} />
      </mesh>
      <Line
        points={trail}
        visible={!trail[0].equals(trail[1])}
        color="#7cc4ff"
        lineWidth={2}
        transparent
        opacity={0.6}
        toneMapped={false}
      />
    </>
  );
}

/** The sun far off: a glowing disc the bloom turns into a flare. */
function Sun() {
  return (
    <mesh position={SUN_AT.clone().normalize().multiplyScalar(900)}>
      <sphereGeometry args={[14, 24, 24]} />
      <meshBasicMaterial color={new Color('#fff1d6').multiplyScalar(6)} toneMapped={false} />
    </mesh>
  );
}

/**
 * A planet with a procedural surface (painted on a canvas from noise once, no image download), a
 * thin atmosphere glowing at its edge and, for Earth, a cloud layer drifting over it.
 */
function Planet({ at, radius, kind, spin }: { at: Vector3; radius: number; kind: 'earth' | 'mars'; spin: number }) {
  const surface = useRef<Mesh>(null);
  const clouds = useRef<Mesh>(null);
  const { map, cloudMap } = useMemo(() => paint(kind), [kind]);
  useEffect(
    () => () => {
      map.dispose();
      cloudMap?.dispose();
    },
    [map, cloudMap],
  );
  useFrame((_, delta) => {
    if (surface.current) surface.current.rotation.y += delta * spin;
    if (clouds.current) clouds.current.rotation.y += delta * spin * 1.4;
  });
  const glow = kind === 'earth' ? '#5aa8ff' : '#e8875a';
  return (
    <group position={at}>
      <mesh ref={surface}>
        <sphereGeometry args={[radius, 96, 96]} />
        <meshStandardMaterial map={map} roughness={kind === 'earth' ? 0.6 : 0.95} />
      </mesh>
      {cloudMap && (
        <mesh ref={clouds}>
          <sphereGeometry args={[radius * 1.012, 96, 96]} />
          <meshStandardMaterial map={cloudMap} transparent depthWrite={false} roughness={1} />
        </mesh>
      )}
      <Atmosphere radius={radius * (kind === 'earth' ? 1.12 : 1.06)} color={glow} strength={kind === 'earth' ? 2.6 : 1.6} />
    </group>
  );
}

/** A glow at the planet's rim: brightest where the view grazes the atmosphere. */
function Atmosphere({ radius, color, strength }: { radius: number; color: string; strength: number }) {
  const uniforms = useMemo(() => ({ glowColor: { value: new Color(color) }, strength: { value: strength } }), [color, strength]);
  return (
    <mesh>
      <sphereGeometry args={[radius, 64, 64]} />
      <shaderMaterial
        uniforms={uniforms}
        side={BackSide}
        transparent
        depthWrite={false}
        blending={AdditiveBlending}
        vertexShader={`
          varying vec3 vNormal;
          varying vec3 vView;
          void main() {
            vec4 world = modelViewMatrix * vec4(position, 1.0);
            vNormal = normalize(normalMatrix * normal);
            vView = normalize(-world.xyz);
            gl_Position = projectionMatrix * world;
          }`}
        fragmentShader={`
          uniform vec3 glowColor;
          uniform float strength;
          varying vec3 vNormal;
          varying vec3 vView;
          void main() {
            // Inside-out sphere: zero at the halo's outer edge, rising towards the planet's limb.
            float rim = pow(clamp(-dot(vNormal, vView), 0.0, 1.0), 1.5);
            gl_FragColor = vec4(glowColor * rim * strength, rim);
          }`}
      />
    </mesh>
  );
}

/** Paints the planet's surface (and Earth's clouds) on equirectangular canvases. */
function paint(kind: 'earth' | 'mars'): { map: CanvasTexture; cloudMap: CanvasTexture | null } {
  const width = 512;
  const height = 256;
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const g = canvas.getContext('2d');
  const image = g?.createImageData(width, height);
  const clouds = kind === 'earth' ? document.createElement('canvas') : null;
  const cg = clouds?.getContext('2d');
  if (clouds) {
    clouds.width = width;
    clouds.height = height;
  }
  const cloudImage = cg?.createImageData(width, height);
  const period = 8;
  for (let y = 0; y < height; y++) {
    const latitude = Math.abs(y / height - 0.5) * 2; // 0 at the equator, 1 at the poles
    for (let x = 0; x < width; x++) {
      const u = (x / width) * period;
      const v = (y / height) * period * 0.5;
      const n = fbm(u, v, 6, period);
      const m = fbm(u + 3.7, v + 1.3, 4, period);
      let rgb: [number, number, number];
      if (kind === 'earth') {
        if (latitude > 0.86 + n * 0.08) rgb = [236, 242, 248];
        else if (n > 0.53) {
          const desert = m > 0.55 && latitude < 0.5;
          rgb = desert ? [190, 160, 100] : [52 + n * 60, 110 + n * 40, 50];
        } else {
          const depth = Math.min(1, (0.53 - n) * 8);
          rgb = [12 + (1 - depth) * 30, 50 + (1 - depth) * 60, 110 + (1 - depth) * 70];
        }
        if (cloudImage) {
          const c = fbm(u * 1.6 + 9, v * 2.2, 5, period * 1.6);
          const alpha = Math.max(0, Math.min(1, (c - 0.5) * 3.2));
          cloudImage.data.set([255, 255, 255, alpha * 230], (y * width + x) * 4);
        }
      } else {
        const dark = Math.max(0, Math.min(1, (m - 0.5) * 5));
        rgb = [140 + n * 120 - dark * 85, 62 + n * 70 - dark * 40, 34 + n * 36 - dark * 18];
        if (latitude > 0.9 + n * 0.05) rgb = [238, 230, 222];
      }
      image?.data.set([rgb[0], rgb[1], rgb[2], 255], (y * width + x) * 4);
    }
  }
  if (g && image) g.putImageData(image, 0, 0);
  if (g && kind === 'mars') paintCraters(g, width, height);
  if (cg && cloudImage) cg.putImageData(cloudImage, 0, 0);
  const map = new CanvasTexture(canvas);
  map.colorSpace = SRGBColorSpace;
  const cloudMap = clouds ? new CanvasTexture(clouds) : null;
  if (cloudMap) cloudMap.colorSpace = SRGBColorSpace;
  return { map, cloudMap };
}

/** Craters (dark floor, bright rim) and a long dark canyon across the equator, like Valles Marineris. */
function paintCraters(g: CanvasRenderingContext2D, width: number, height: number) {
  g.save();
  g.globalCompositeOperation = 'multiply';
  g.strokeStyle = 'rgba(70, 30, 18, 0.55)';
  g.lineCap = 'round';
  g.lineWidth = 2.5;
  g.beginPath();
  g.moveTo(width * 0.18, height * 0.55);
  for (let i = 1; i <= 12; i++) {
    g.lineTo(width * (0.18 + i * 0.025), height * (0.55 - i * 0.004 + Math.sin(i * 1.7) * 0.012));
  }
  g.stroke();
  g.restore();
  for (let i = 0; i < 140; i++) {
    const x = hash2(i, 11) * width;
    const y = height * (0.12 + hash2(i, 12) * 0.76);
    const r = 1.5 + hash2(i, 13) ** 3 * 14;
    const floor = g.createRadialGradient(x, y, 0, x, y, r);
    floor.addColorStop(0, 'rgba(60, 25, 14, 0.45)');
    floor.addColorStop(0.75, 'rgba(60, 25, 14, 0.25)');
    floor.addColorStop(0.9, 'rgba(235, 170, 120, 0.35)');
    floor.addColorStop(1, 'rgba(235, 170, 120, 0)');
    g.fillStyle = floor;
    g.beginPath();
    g.ellipse(x, y, r, r * 0.8, 0, 0, Math.PI * 2);
    g.fill();
  }
}
