import { lazy, Suspense, useState } from 'react';
import type { Scene3DProps } from '../scene/Scene3D';
import { TerrainMap } from './TerrainMap';
import { hasWebGL, savedView, saveView, type View } from './viewPreference';

// three.js is large: only browsers that show the 3D view download it.
const Scene3D = lazy(() => import('../scene/Scene3D'));

/**
 * The landing site in 3D, or as the 2D map (chosen by the user, and always without WebGL). The
 * intro film is played by the app right at login (see `IntroGate`), not here.
 */
export function MapView(props: Scene3DProps) {
  const [webgl] = useState(hasWebGL);
  const [view, setView] = useState<View>(() => (webgl ? (savedView() ?? '3d') : '2d'));

  const choose = (next: View) => {
    setView(next);
    saveView(next);
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
          <button
            className={`small ${view === '3d' ? '' : 'secondary'}`}
            disabled={!webgl}
            title={webgl ? '' : 'WebGL is not available'}
            onClick={() => choose('3d')}
          >
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
        <TerrainMap
          api={props.api}
          site={site}
          rover={props.rover}
          preview={props.preview}
          target={props.target}
          onCellClick={props.onCellClick}
        />
      )}
    </div>
  );
}
