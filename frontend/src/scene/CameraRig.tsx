import { OrbitControls } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useEffect, useRef, type ComponentRef, type RefObject } from 'react';
import { Vector3, type Group } from 'three';

export type CameraMode = 'orbit' | 'follow' | 'top';

/** Seconds of the opening "landing" flight from high above the crater. */
const INTRO_SECONDS = 3;
const ORBIT_OFFSET = new Vector3(9, 7, 13);
const FOLLOW_OFFSET = new Vector3(0, 2.4, 5);
/** Meters the camera keeps above the ground. */
const CAMERA_CLEARANCE = 1.5;

/**
 * Orbit: drag and zoom around the rover (the target follows it). Follow: behind the rover, turning
 * with it. Top-down: straight above. Every mode eases towards its pose, so switching never jumps.
 */
/**
 * @param focus a point to look at instead of the rover (e.g. where a photo was taken); orbit mode flies
 *              there and keeps it as the target until it is cleared
 * @param groundAt ground height under a world point; the camera never goes below it (into a hill)
 */
export function CameraRig({
  mode,
  roverRef,
  focus = null,
  groundAt,
}: {
  mode: CameraMode;
  roverRef: RefObject<Group | null>;
  focus?: Vector3 | null;
  groundAt?: (x: number, z: number) => number;
}) {
  const controls = useRef<ComponentRef<typeof OrbitControls>>(null);
  const elapsed = useRef(0);
  const desired = useRef(new Vector3());
  const flyUntil = useRef(0);

  // A new focus: fly over to it for a moment, then the user is free to look around it.
  useEffect(() => {
    if (focus) flyUntil.current = elapsed.current + 2;
  }, [focus]);

  useFrame((state, delta) => {
    const rover = roverRef.current;
    if (!rover) return;
    elapsed.current += delta;
    const camera = state.camera;
    const at = rover.position;
    const k = 1 - Math.exp(-delta * (elapsed.current < INTRO_SECONDS ? 1.6 : 4));

    if (mode === 'follow') {
      desired.current
        .copy(FOLLOW_OFFSET)
        .applyAxisAngle(new Vector3(0, 1, 0), rover.rotation.y)
        .add(at);
      camera.position.lerp(desired.current, k);
      camera.lookAt(at.x, at.y + 0.7, at.z);
    } else if (mode === 'top') {
      desired.current.set(at.x, at.y + 45, at.z + 0.01);
      camera.position.lerp(desired.current, k);
      camera.lookAt(at);
    } else if (controls.current) {
      const look = focus ?? at;
      controls.current.target.lerp(look, k);
      if (elapsed.current < INTRO_SECONDS || elapsed.current < flyUntil.current) {
        camera.position.lerp(desired.current.copy(look).add(ORBIT_OFFSET), k);
      }
      controls.current.update();
    }
    if (groundAt) {
      const floor = groundAt(camera.position.x, camera.position.z) + CAMERA_CLEARANCE;
      if (camera.position.y < floor) camera.position.y = floor;
    }
  });

  return mode === 'orbit' ? (
    <OrbitControls ref={controls} makeDefault enableDamping maxPolarAngle={Math.PI * 0.47} minDistance={3} maxDistance={160} />
  ) : null;
}
