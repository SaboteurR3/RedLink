import { OrbitControls } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useRef, type ComponentRef, type RefObject } from 'react';
import { Vector3, type Group } from 'three';

export type CameraMode = 'orbit' | 'follow' | 'top';

/** Seconds of the opening "landing" flight from high above the crater. */
const INTRO_SECONDS = 3;
const ORBIT_OFFSET = new Vector3(9, 7, 13);
const FOLLOW_OFFSET = new Vector3(0, 2.4, 5);

/**
 * Orbit: drag and zoom around the rover (the target follows it). Follow: behind the rover, turning
 * with it. Top-down: straight above. Every mode eases towards its pose, so switching never jumps.
 */
export function CameraRig({ mode, roverRef }: { mode: CameraMode; roverRef: RefObject<Group | null> }) {
  const controls = useRef<ComponentRef<typeof OrbitControls>>(null);
  const elapsed = useRef(0);
  const desired = useRef(new Vector3());

  useFrame((state, delta) => {
    const rover = roverRef.current;
    if (!rover) return;
    elapsed.current += delta;
    const camera = state.camera;
    const at = rover.position;
    const k = 1 - Math.exp(-delta * (elapsed.current < INTRO_SECONDS ? 1.6 : 4));

    if (mode === 'follow') {
      desired.current.copy(FOLLOW_OFFSET).applyAxisAngle(new Vector3(0, 1, 0), rover.rotation.y).add(at);
      camera.position.lerp(desired.current, k);
      camera.lookAt(at.x, at.y + 0.7, at.z);
    } else if (mode === 'top') {
      desired.current.set(at.x, at.y + 45, at.z + 0.01);
      camera.position.lerp(desired.current, k);
      camera.lookAt(at);
    } else if (controls.current) {
      controls.current.target.lerp(at, k);
      if (elapsed.current < INTRO_SECONDS) {
        camera.position.lerp(desired.current.copy(at).add(ORBIT_OFFSET), k);
      }
      controls.current.update();
    }
  });

  return mode === 'orbit' ? (
    <OrbitControls ref={controls} makeDefault enableDamping maxPolarAngle={Math.PI * 0.47} minDistance={3} maxDistance={90} />
  ) : null;
}
