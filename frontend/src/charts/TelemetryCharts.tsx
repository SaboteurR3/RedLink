import { useEffect, useMemo, useState } from 'react';
import type { ApiClient } from '../api/client';
import type { Rover } from '../api/types';
import { addPoint, domain, linePath, nearestIndex, scaleX, scaleY, type Box, type StatePoint } from './series';

type Measure = { key: 'battery' | 'storage' | 'temperature'; title: string; unit: string; fixed?: [number, number] };

const MEASURES: Measure[] = [
  { key: 'battery', title: 'Battery', unit: '%', fixed: [0, 100] },
  { key: 'storage', title: 'Storage used', unit: '%', fixed: [0, 100] },
  { key: 'temperature', title: 'Temperature', unit: '°C' },
];
const BOX: Box = { width: 320, height: 84, left: 30, right: 40, top: 8, bottom: 16 };

/**
 * How the rover's battery, storage and temperature changed, from its ROVER_STATE reports (Mars
 * time). One small chart per measure, each with its own axis; a live report adds a point.
 */
export function TelemetryCharts({ api, roverId, rover }: { api: ApiClient; roverId: number; rover: Rover | null }) {
  const [points, setPoints] = useState<StatePoint[]>([]);
  const [table, setTable] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .telemetry(roverId, 'ROVER_STATE', 200)
      .then((page) =>
        setPoints(
          page.content
            .map((r) => ({ t: Date.parse(r.roverTime), ...stateOf(r.payload) }))
            .filter((p): p is StatePoint => p.battery !== undefined)
            .sort((a, b) => a.t - b.t),
        ),
      )
      .catch(() => setError('History could not be loaded'));
  }, [api, roverId]);

  // Each new report on the live stream adds a point.
  const reportedAt = rover?.lastKnown.reportedAt;
  useEffect(() => {
    if (!rover || !reportedAt) return;
    const k = rover.lastKnown;
    setPoints((all) => addPoint(all, { t: Date.parse(reportedAt), battery: k.batteryPercent, storage: k.storageUsedPercent, temperature: k.temperatureCelsius }));
  }, [rover, reportedAt]);

  return (
    <div className="panel charts">
      <div className="modal-head">
        <h2>History</h2>
        <button className="small secondary" onClick={() => setTable(!table)} aria-pressed={table}>
          {table ? 'Charts' : 'Table'}
        </button>
      </div>
      {error && <p className="error">{error}</p>}
      {points.length < 2 && !error && <p className="muted">Not enough reports yet.</p>}
      {points.length >= 2 && !table && MEASURES.map((m) => <Chart key={m.key} measure={m} points={points} />)}
      {points.length >= 2 && table && (
        <div className="chart-table">
          <table>
            <thead>
              <tr>
                <th>Mars time</th>
                <th>Battery %</th>
                <th>Storage %</th>
                <th>°C</th>
              </tr>
            </thead>
            <tbody>
              {[...points].reverse().slice(0, 30).map((p) => (
                <tr key={p.t}>
                  <td>{new Date(p.t).toLocaleTimeString()}</td>
                  <td>{p.battery}</td>
                  <td>{p.storage}</td>
                  <td>{p.temperature}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function Chart({ measure, points }: { measure: Measure; points: StatePoint[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const ts = useMemo(() => points.map((p) => p.t), [points]);
  const values = useMemo(() => points.map((p) => p[measure.key]), [points, measure.key]);
  const x: [number, number] = [ts[0] ?? 0, ts[ts.length - 1] ?? 1];
  const y = domain(values, measure.fixed);
  // Reports come every few seconds while the rover works; a longer silence is a gap, not a slope.
  const path = linePath(ts, values, x, y, BOX, 10 * 60_000);
  const last = points.length - 1;
  const shown = hover ?? last;
  const sx = scaleX(ts[shown] ?? 0, x, BOX);
  const sy = scaleY(values[shown] ?? 0, y, BOX);
  const ticks = [y[0], (y[0] + y[1]) / 2, y[1]];

  return (
    <figure className="chart">
      <figcaption>
        <span>{measure.title}</span>
        {hover !== null && (
          <span className="chart-tip">
            {values[hover]} {measure.unit} · {new Date(ts[hover] ?? 0).toLocaleTimeString()}
          </span>
        )}
      </figcaption>
      <svg
        viewBox={`0 0 ${BOX.width} ${BOX.height}`}
        role="img"
        aria-label={`${measure.title} over time, now ${values[last]} ${measure.unit}`}
        onMouseMove={(e) => {
          const r = e.currentTarget.getBoundingClientRect();
          const vx = ((e.clientX - r.left) / r.width) * BOX.width;
          const t = x[0] + ((vx - BOX.left) / (BOX.width - BOX.left - BOX.right)) * (x[1] - x[0]);
          setHover(nearestIndex(ts, t));
        }}
        onMouseLeave={() => setHover(null)}
      >
        {ticks.map((v) => (
          <g key={v}>
            <line className="chart-grid" x1={BOX.left} x2={BOX.width - BOX.right} y1={scaleY(v, y, BOX)} y2={scaleY(v, y, BOX)} />
            <text className="chart-axis" x={BOX.left - 4} y={scaleY(v, y, BOX) + 3} textAnchor="end">
              {Math.round(v)}
            </text>
          </g>
        ))}
        <path className="chart-line" d={path} />
        {hover !== null && <line className="chart-cross" x1={sx} x2={sx} y1={BOX.top} y2={BOX.height - BOX.bottom} />}
        <circle className="chart-dot" cx={sx} cy={sy} r={4} />
        {hover === null && (
          <text className="chart-label" x={sx + 7} y={sy + 4}>
            {values[last]} {measure.unit}
          </text>
        )}
      </svg>
    </figure>
  );
}

function stateOf(payload: unknown): Partial<StatePoint> {
  const p = payload as { batteryPercent?: number; storageUsedPercent?: number; temperatureCelsius?: number };
  return { battery: p.batteryPercent, storage: p.storageUsedPercent, temperature: p.temperatureCelsius };
}
