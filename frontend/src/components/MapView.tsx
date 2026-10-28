import { lazy, Suspense, useState } from 'react';
import type { Scene3DProps } from '../scene/Scene3D';
import { TerrainMap } from './TerrainMap';

// three.js is large: only browsers that show the 3D view download it.
const Scene3D = lazy(() => import('../scene/Scene3D'));

type View = '2d' | '3d';
const STORAGE_KEY = 'redlink.view';

function hasWebGL(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'));
  } catch {
    return false;
  }
}

function savedView(): View | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === '2d' || value === '3d' ? value : null;
  } catch {
    return null;
  }
}

/** The landing site in 3D, or as the 2D map (chosen by the user, and always without WebGL). */
export function MapView(props: Scene3DProps) {
  const [webgl] = useState(hasWebGL);
  const [view, setView] = useState<View>(() => (webgl ? (savedView() ?? '3d') : '2d'));

  const choose = (next: View) => {
    setView(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // private window: the choice just isn't remembered
    }
  };

  const { site } = props;
  return (
    <div className="panel map">
      <div className="map-title">
        <h2>{site.name}</h2>
        <span className="muted">
          {site.width}×{site.height} m · elevation {site.minElevationMeters}–{site.maxElevationMeters} m
        </span>
        <span className="view-toggle">
          <button className={`small ${view === '3d' ? '' : 'secondary'}`} disabled={!webgl} title={webgl ? '' : 'WebGL is not available'} onClick={() => choose('3d')}>
            3D
          </button>
          <button className={`small ${view === '2d' ? '' : 'secondary'}`} onClick={() => choose('2d')}>
            2D
          </button>
        </span>
      </div>
      {view === '3d' && webgl ? (
        <Suspense fallback={<p className="muted scene-loading">Loading 3D view…</p>}>
          <Scene3D {...props} />
        </Suspense>
      ) : (
        <TerrainMap api={props.api} site={site} rover={props.rover} />
      )}
    </div>
  );
}
