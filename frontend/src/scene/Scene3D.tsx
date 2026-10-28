import { Sparkles } from '@react-three/drei';
import { Canvas } from '@react-three/fiber';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Vector3, type Group } from 'three';
import type { ApiClient } from '../api/client';
import type { Heading, Photo, Rover, Site, UplinkTransmission } from '../api/types';
import { loadGrid } from '../terrain/pixels';
import { CameraRig, type CameraMode } from './CameraRig';
import { cellHeight, cellToWorld, type Grid } from './geometry';
import { Beams, Ghost, PhotoPins } from './Markers';
import { Rover as RoverModel } from './RoverModel';
import { CraterRim, Rocks, Terrain } from './Terrain';

const SKY = '#c99a72';
const STORM_SKY = '#a8714b';
const MODES: Array<{ mode: CameraMode; label: string }> = [
  { mode: 'orbit', label: 'Orbit' },
  { mode: 'follow', label: 'Follow' },
  { mode: 'top', label: 'Top-down' },
];

export type Forecast = { x: number; y: number; heading: Heading };

export type Scene3DProps = {
  api: ApiClient;
  site: Site;
  rover: Rover;
  uplinks: UplinkTransmission[];
  photos: Photo[];
  /** Where the last mission planned here will end, while it runs. */
  forecast: Forecast | null;
  /** The rover's latest weather report says a dust storm is on. */
  dustStorm: boolean;
};

/**
 * The landing site in 3D, built from the same files the rover simulation drives on. Everything in
 * it comes from the API and the event stream: the rover moves when telemetry says so, beams fly on
 * the real transmission times. Default export, so it can be lazy-loaded with three.js.
 */
export default function Scene3D({ api, site, rover, uplinks, photos, forecast, dustStorm }: Scene3DProps) {
  const [grid, setGrid] = useState<Grid | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<CameraMode>('orbit');
  const roverRef = useRef<Group>(null);

  useEffect(() => {
    let cancelled = false;
    loadGrid(api, site)
      .then((loaded) => !cancelled && setGrid(loaded))
      .catch(() => !cancelled && setError('Terrain could not be loaded'));
    return () => {
      cancelled = true;
    };
  }, [api, site]);

  const { x, y, heading, mode: roverMode } = rover.lastKnown;
  // Beams aim at where the rover is known to be (a little above its mast).
  const beamTarget = useMemo(() => {
    if (!grid) return new Vector3();
    const [wx, wz] = cellToWorld(grid, x, y);
    return new Vector3(wx, cellHeight(grid, x, y) + 1.6, wz);
  }, [grid, x, y]);
  const half = grid ? (Math.max(grid.width, grid.height) * grid.metersPerCell) / 2 : 32;

  return (
    <div className="scene-frame">
      {error && <p className="error">{error}</p>}
      {!grid && !error && <p className="muted scene-loading">Loading terrain…</p>}
      {grid && (
        <Canvas shadows="percentage" dpr={[1, 2]} camera={{ position: [0, 120, 140], fov: 50, near: 0.1, far: 900 }}>
          <color attach="background" args={[dustStorm ? STORM_SKY : SKY]} />
          {/* A dust storm closes the horizon in and fills the air. */}
          <fog attach="fog" args={dustStorm ? [STORM_SKY, half * 0.2, half * 2] : [SKY, half * 1.2, half * 6]} />
          <hemisphereLight args={['#f4d7b5', '#5a3420', 0.7]} />
          {/* Low sun in the west, as in the rover's photos. */}
          <directionalLight
            castShadow
            position={[-half, half * 0.8, half * 0.3]}
            intensity={2.4}
            color="#ffe6c7"
            shadow-mapSize={[2048, 2048]}
            shadow-camera-left={-half}
            shadow-camera-right={half}
            shadow-camera-top={half}
            shadow-camera-bottom={-half}
            shadow-camera-far={half * 4}
          />
          <Terrain grid={grid} />
          <Rocks grid={grid} />
          <CraterRim grid={grid} />
          <RoverModel grid={grid} x={x} y={y} heading={heading} mode={roverMode} groupRef={roverRef} />
          <Beams uplinks={uplinks} target={beamTarget} />
          <PhotoPins grid={grid} photos={photos} />
          {forecast && <Ghost grid={grid} x={forecast.x} y={forecast.y} heading={forecast.heading} />}
          <Sparkles key={dustStorm ? 'storm' : 'calm'} count={dustStorm ? 1500 : 250} scale={[half * 2, 10, half * 2]} position={[0, 5, 0]} size={2.5} speed={0.35} color="#e9c39c" opacity={0.6} />
          <CameraRig mode={mode} roverRef={roverRef} />
        </Canvas>
      )}
      <div className="scene-hud">
        <span className={`chip ${roverMode === 'SAFE_MODE' ? 'chip-FAILED' : 'chip-COMPLETED'}`}>{roverMode.replace('_', ' ')}</span>
        {MODES.map(({ mode: m, label }) => (
          <button key={m} className={`small ${m === mode ? '' : 'secondary'}`} onClick={() => setMode(m)}>
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}
