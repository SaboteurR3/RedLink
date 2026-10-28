import { Sparkles } from '@react-three/drei';
import { Canvas } from '@react-three/fiber';
import { Bloom, EffectComposer, Vignette } from '@react-three/postprocessing';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Vector3, type Group } from 'three';
import type { ApiClient } from '../api/client';
import type { Heading, LinkStatus, Photo, PlanPreview, Rover, Sample, Site, UplinkTransmission } from '../api/types';
import { loadGrid } from '../terrain/pixels';
import { CameraRig, type CameraMode } from './CameraRig';
import { cellHeight, cellToWorld, type Grid } from './geometry';
import { DustWall } from './DustWall';
import { Beams, Ghost, PhotoCone, PhotoPins } from './Markers';
import { Minimap } from './Minimap';
import { Orbiter } from './Orbiter';
import { PathPreview, TargetMarker } from './Plans';
import { Rover as RoverModel, type RoverActivity } from './RoverModel';
import { Sky } from './Sky';
import { demoStorm, scenePhase, solClock } from './sol';
import { Landscape, Pebbles } from './Landscape';
import { landscapeHeight } from './relief';
import { Rocks, Terrain } from './Terrain';
import { Tracks } from './Tracks';
import { DrillMarks } from './DrillMarks';

const MODES: Array<{ mode: CameraMode; label: string }> = [
  { mode: 'orbit', label: 'Orbit' },
  { mode: 'follow', label: 'Follow' },
  { mode: 'top', label: 'Top-down' },
];

export type Forecast = { x: number; y: number; heading: Heading };
const NO_SAMPLES: Sample[] = [];

export type Focus = { x: number; y: number; heading?: Heading; camera?: Photo['camera'] };

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
  /** A dry run to draw on the terrain (the console's command or plan, or a route to a clicked cell). */
  preview: PlanPreview | null;
  /** The cell the user clicked to drive to. */
  target: { x: number; y: number } | null;
  onCellClick?: (x: number, y: number) => void;
  /** A cell to show; with a camera and heading, where a photo was taken and what it saw. Cleared by the next camera mode change. */
  focus?: Focus | null;
  onClearFocus?: () => void;
  /** What the rover is executing now (by its last report), for its animations. */
  activity?: RoverActivity;
  /** When the latest photo arrived (ms), for the camera flash. */
  flashAt?: number;
  /** Drill samples taken, for their marks on the ground and rocks. */
  samples?: Sample[];
  /** The deep-space link, for the relay orbiter's passes. */
  link?: LinkStatus | null;
};

/**
 * The landing site in 3D, built from the same files the rover simulation drives on. Everything in
 * it comes from the API and the event stream: the rover moves when telemetry says so, beams fly on
 * the real transmission times. Default export, so it can be lazy-loaded with three.js.
 */
export default function Scene3D({
  api,
  site,
  rover,
  uplinks,
  photos,
  forecast,
  dustStorm: reportedStorm,
  preview,
  target,
  onCellClick,
  focus = null,
  onClearFocus,
  activity = null,
  flashAt = 0,
  link = null,
  samples = NO_SAMPLES,
}: Scene3DProps) {
  const dustStorm = reportedStorm || demoStorm();
  const [grid, setGrid] = useState<Grid | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<CameraMode>('orbit');
  const [trail, setTrail] = useState<Array<{ x: number; y: number }>>([]);
  const [clock, setClock] = useState(() => solClock(scenePhase()));
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

  // Wheel tracks: where the rover reported being (history first, then every new report).
  useEffect(() => {
    api
      .telemetry(rover.id, 'ROVER_STATE', 200)
      .then((page) => {
        const cells = page.content
          .map((r) => ({ t: Date.parse(r.roverTime), ...(r.payload as { x: number; y: number }) }))
          .sort((a, b) => a.t - b.t)
          .map(({ x, y }) => ({ x, y }));
        setTrail((live) => [...cells, ...live]);
      })
      .catch(() => undefined); // tracks are decoration
  }, [api, rover.id]);

  const { x, y, heading, mode: roverMode } = rover.lastKnown;
  useEffect(() => {
    setTrail((cells) => {
      const last = cells[cells.length - 1];
      return last && last.x === x && last.y === y ? cells : [...cells, { x, y }].slice(-1000);
    });
  }, [x, y]);

  useEffect(() => {
    const timer = setInterval(() => setClock(solClock(scenePhase())), 1000);
    return () => clearInterval(timer);
  }, []);

  // Beams aim at where the rover is known to be (a little above its mast).
  const beamTarget = useMemo(() => {
    if (!grid) return new Vector3();
    const [wx, wz] = cellToWorld(grid, x, y);
    return new Vector3(wx, cellHeight(grid, x, y) + 1.6, wz);
  }, [grid, x, y]);
  const focusPoint = useMemo(() => {
    if (!grid || !focus) return null;
    const [fx, fz] = cellToWorld(grid, focus.x, focus.y);
    return new Vector3(fx, cellHeight(grid, focus.x, focus.y), fz);
  }, [grid, focus]);
  const half = grid ? (Math.max(grid.width, grid.height) * grid.metersPerCell) / 2 : 32;
  const groundAt = useCallback((wx: number, wz: number) => (grid ? landscapeHeight(grid, wx, wz) : 0), [grid]);

  return (
    <div className="scene-frame">
      {error && <p className="error">{error}</p>}
      {!grid && !error && <p className="muted scene-loading">Loading terrain…</p>}
      {grid && (
        <Canvas shadows="percentage" dpr={[1, 2]} camera={{ position: [0, 120, 140], fov: 50, near: 0.1, far: 1600 }}>
          <Sky half={half} dustStorm={dustStorm} />
          <Orbiter link={link} half={half} />
          <DustWall half={half} active={dustStorm} />
          <Terrain grid={grid} onCellClick={onCellClick} />
          <Tracks grid={grid} cells={trail} />
          {preview && <PathPreview grid={grid} preview={preview} />}
          {target && <TargetMarker grid={grid} x={target.x} y={target.y} />}
          <Rocks grid={grid} />
          <Landscape grid={grid} />
          <Pebbles grid={grid} />
          <RoverModel
            grid={grid}
            x={x}
            y={y}
            heading={heading}
            mode={roverMode}
            activity={activity}
            flashAt={flashAt}
            groupRef={roverRef}
          />
          <Beams uplinks={uplinks} target={beamTarget} />
          <PhotoPins grid={grid} photos={photos} />
          <DrillMarks grid={grid} samples={samples} />
          {focus?.camera && focus.heading && (
            <PhotoCone grid={grid} x={focus.x} y={focus.y} heading={focus.heading} camera={focus.camera} />
          )}
          {forecast && <Ghost grid={grid} x={forecast.x} y={forecast.y} heading={forecast.heading} />}
          <Sparkles
            key={dustStorm ? 'storm' : 'calm'}
            count={dustStorm ? 1500 : 250}
            scale={[half * 2, 10, half * 2]}
            position={[0, 5, 0]}
            size={2.5}
            speed={dustStorm ? 1.4 : 0.35}
            color="#e9c39c"
            opacity={0.6}
          />
          <CameraRig mode={mode} roverRef={roverRef} focus={focusPoint} groundAt={groundAt} />
          {/* Glow on beams, Earth, lights and the camera flash; darker corners frame the scene. */}
          <EffectComposer multisampling={0}>
            <Bloom intensity={0.8} luminanceThreshold={0.7} luminanceSmoothing={0.25} mipmapBlur />
            <Vignette offset={0.3} darkness={0.55} />
          </EffectComposer>
        </Canvas>
      )}
      {roverMode === 'SAFE_MODE' && (
        <div className="safe-alert" role="alert">
          <p>⚠ SAFE MODE — only REBOOT is accepted</p>
        </div>
      )}
      {grid && <Minimap grid={grid} rover={{ x, y, heading }} target={target} focus={focus} onCellClick={onCellClick} />}
      <div className="scene-hud">
        <span className={`chip ${roverMode === 'SAFE_MODE' ? 'chip-FAILED' : 'chip-COMPLETED'}`}>
          {roverMode.replace('_', ' ')}
        </span>
        {MODES.map(({ mode: m, label }) => (
          <button
            key={m}
            className={`small ${m === mode ? '' : 'secondary'}`}
            onClick={() => {
              setMode(m);
              onClearFocus?.();
            }}
          >
            {label}
          </button>
        ))}
        <span className="sol-clock" title="Local solar time at the site (a sol is compressed to 12 minutes)">
          ☀ {clock}
        </span>
      </div>
    </div>
  );
}
