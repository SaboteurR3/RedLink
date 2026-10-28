import type { Rover } from '../api/types';
import { ago, useNow } from '../hooks/useNow';

/** The rover as Earth last heard it: always the past, never live truth. */
export function TelemetryPanel({ rover }: { rover: Rover }) {
  const now = useNow();
  const state = rover.lastKnown;
  const battery = Math.round(state.batteryPercent * 10) / 10;

  return (
    <div className="panel telemetry">
      <h2>{rover.name}</h2>
      <p className="muted">
        {state.reportedAt ? `Last report ${ago(state.reportedAt, now)} (Mars time)` : 'Nothing heard since landing'}
      </p>
      <dl>
        <dt>Mode</dt>
        <dd>
          <span className={`chip ${state.mode === 'SAFE_MODE' ? 'chip-FAILED' : 'chip-COMPLETED'}`}>{state.mode.replace('_', ' ')}</span>
        </dd>
        <dt>Battery</dt>
        <dd>
          <span className={`meter ${battery < 20 ? 'low' : ''}`}>
            <span style={{ width: `${battery}%` }} />
          </span>{' '}
          {battery} %
        </dd>
        <dt>Position</dt>
        <dd>
          ({state.x}, {state.y}) facing {state.heading}
        </dd>
        <dt>Temperature</dt>
        <dd>{state.temperatureCelsius} °C</dd>
        <dt>Storage</dt>
        <dd>{state.storageUsedPercent} % used</dd>
      </dl>
    </div>
  );
}
