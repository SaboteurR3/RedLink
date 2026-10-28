import { useFrame, useThree } from '@react-three/fiber';
import { useEffect, useRef } from 'react';
import { Color, Fog, type DirectionalLight, type HemisphereLight, type Mesh } from 'three';
import { EARTH } from './Markers';
import { daylight, dusk, mixColor, scenePhase, sunDirection } from './sol';
import { StarField } from './StarField';

const DAY_SKY = '#c99a72';
const DUSK_SKY = '#4f6d9a'; // Mars sunsets are blue: fine dust scatters blue light forward
const NIGHT_SKY = '#06070c';
const STORM_SKY = '#7d5236';

/**
 * The sky over the site through a sol: sun position, colour and strength, sky and fog colour,
 * stars at night, Phobos and Deimos crossing, and Earth as a small blue light where the radio
 * beams come from. A dust storm dims and browns everything.
 */
export function Sky({ half, dustStorm }: { half: number; dustStorm: boolean }) {
  const { scene } = useThree();
  const sun = useRef<DirectionalLight>(null);
  const hemi = useRef<HemisphereLight>(null);
  const stars = useRef(0);
  const phobos = useRef<Mesh>(null);
  const deimos = useRef<Mesh>(null);

  useEffect(() => {
    scene.background = new Color(DAY_SKY);
    scene.fog = new Fog(DAY_SKY, half * 2.5, half * 30);
    return () => {
      scene.fog = null;
    };
  }, [scene, half]);

  useFrame(() => {
    const phase = scenePhase();
    const day = daylight(phase);
    const blue = dusk(phase);
    let sky = mixColor(NIGHT_SKY, DAY_SKY, day);
    sky = mixColor(sky, DUSK_SKY, blue * 0.55);
    if (dustStorm) sky = mixColor(sky, STORM_SKY, 0.6);
    (scene.background as Color).set(sky);
    if (scene.fog instanceof Fog) {
      scene.fog.color.set(sky);
      scene.fog.near = dustStorm ? half * 0.2 : half * 2.5;
      scene.fog.far = dustStorm ? half * 2 : half * 30;
    }

    const [dx, dy, dz] = sunDirection(phase);
    if (sun.current) {
      sun.current.position.set(dx * half * 2, Math.max(dy, -0.2) * half * 2, dz * half * 2);
      sun.current.intensity = 2.6 * day * (dustStorm ? 0.45 : 1);
      sun.current.color.set(mixColor('#a9c4ff', '#ffe6c7', Math.min(1, day * 1.6)));
    }
    if (hemi.current) hemi.current.intensity = (0.12 + 0.6 * day) * (dustStorm ? 0.7 : 1);
    // Stars come out as the sky darkens (from dusk on); a dust storm hides them.
    stars.current = dustStorm ? 0 : Math.min(1, Math.max(0, (0.55 - day) / 0.4));

    // Phobos circles three times a sol, Deimos a bit less than once (both rise in the west, as on Mars).
    place(phobos.current, -phase * 3 * 2 * Math.PI, 260, 0.55);
    place(deimos.current, -phase * 0.8 * 2 * Math.PI + 1.3, 320, 0.4);
  });

  return (
    <>
      <hemisphereLight ref={hemi} args={['#f4d7b5', '#5a3420', 0.7]} />
      <directionalLight
        ref={sun}
        castShadow
        position={[-half, half * 0.8, half * 0.3]}
        intensity={2.4}
        shadow-mapSize={[2048, 2048]}
        shadow-camera-left={-half}
        shadow-camera-right={half}
        shadow-camera-top={half}
        shadow-camera-bottom={-half}
        shadow-camera-far={half * 6}
      />
      <StarField visibility={stars} />
      <mesh ref={phobos}>
        <sphereGeometry args={[4, 16, 16]} />
        <meshBasicMaterial color="#9a8b7a" fog={false} />
      </mesh>
      <mesh ref={deimos}>
        <sphereGeometry args={[2.4, 16, 16]} />
        <meshBasicMaterial color="#b3a593" fog={false} />
      </mesh>
      {/* Earth: a bright blue point in the Martian sky, where every uplink beam starts. */}
      <mesh position={EARTH}>
        <sphereGeometry args={[1.8, 16, 16]} />
        <meshBasicMaterial color="#7cc4ff" toneMapped={false} fog={false} />
      </mesh>
    </>
  );
}

/** A moon on a tilted circle around the site; hidden while below the horizon. */
function place(moon: Mesh | null, angle: number, radius: number, tilt: number) {
  if (!moon) return;
  const x = Math.cos(angle) * radius;
  const y = Math.sin(angle) * radius * tilt;
  const z = -Math.sin(angle) * radius * 0.35 - 60;
  moon.position.set(x, y, z);
  moon.visible = y > -5;
}
