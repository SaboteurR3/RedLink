import { useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { Command, Mission, RouteProposal } from '../api/types';
import { describe } from './CommandTimeline';

export type RouteState =
  | { kind: 'loading'; x: number; y: number }
  | { kind: 'ready'; x: number; y: number; route: RouteProposal }
  | { kind: 'error'; x: number; y: number; message: string };

/**
 * The drive to a clicked cell: what it takes, and a button to send it as a mission. The route and
 * its check come from the server (the same simulation as every mission).
 */
export function RoutePanel({
  api,
  roverId,
  route,
  canDrive,
  onSent,
  onClose,
}: {
  api: ApiClient;
  roverId: number;
  route: RouteState;
  canDrive: boolean;
  onSent: (mission: Mission, steps: Command[]) => void;
  onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const where = `(${route.x}, ${route.y})`;

  const drive = async () => {
    if (route.kind !== 'ready') return;
    setBusy(true);
    setError(null);
    try {
      const { mission } = await api.submitMission(roverId, { name: `Drive to ${where}`, steps: route.route.steps });
      onSent(mission, mission.steps);
    } catch (e) {
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="route-panel" role="dialog" aria-label={`Drive to ${where}`}>
      <div>
        <strong>Drive to {where}</strong>
        {route.kind === 'loading' && <div className="route-steps">Planning a route around rocks and slopes…</div>}
        {route.kind === 'error' && <div className="error">{route.message}</div>}
        {route.kind === 'ready' && (
          <>
            <div className="route-steps">{route.route.steps.map((s) => describe(s)).join(' → ')}</div>
            <div className={route.route.preview.feasible ? 'ok' : 'error'}>
              {route.route.preview.feasible
                ? `${route.route.steps.length} commands · ${route.route.preview.marsSeconds} s on Mars · battery after: ${route.route.preview.end.batteryPercent} %`
                : `Not possible: step ${route.route.preview.problem?.step}: ${route.route.preview.problem?.reason}`}
            </div>
          </>
        )}
        {error && <div className="error">{error}</div>}
      </div>
      <div className="console-row">
        {canDrive && route.kind === 'ready' && route.route.preview.feasible && (
          <button disabled={busy} onClick={() => void drive()}>
            {busy ? 'Sending…' : 'Drive there'}
          </button>
        )}
        <button className="secondary" onClick={onClose}>
          Close
        </button>
      </div>
    </div>
  );
}
