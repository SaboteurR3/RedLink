import { useCallback, useEffect, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { Command, Mission } from '../api/types';

/**
 * The rover's missions: status and progress derived by the server from the steps, the forecast, and
 * Cancel while a step is still on Earth (offered only when the server's `_links.cancel` says so).
 *
 * @param refresh changes whenever a mission step changed; the list is reloaded then
 */
export function MissionsPanel({
  api,
  roverId,
  refresh,
  canCancel,
  onCommand,
}: {
  api: ApiClient;
  roverId: number;
  refresh: number;
  canCancel: boolean;
  onCommand: (c: Command) => void;
}) {
  const [missions, setMissions] = useState<Mission[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .missions(roverId, 8)
      .then((page) => setMissions(page.content))
      .catch(() => setError('Missions could not be loaded'));
  }, [api, roverId]);

  // Reload a moment after a step changed, so several changes in a row cost one request.
  useEffect(() => {
    const timer = setTimeout(load, 400);
    return () => clearTimeout(timer);
  }, [load, refresh]);

  const cancel = async (mission: Mission) => {
    if (!mission._links.cancel) return;
    setError(null);
    try {
      const updated = await api.follow<Mission>(mission._links.cancel);
      updated.steps.forEach(onCommand);
      setMissions((all) => all.map((m) => (m.id === updated.id ? updated : m)));
    } catch (e) {
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    }
  };

  return (
    <div className="panel missions">
      <h2>Missions</h2>
      {error && <p className="error">{error}</p>}
      {missions.length === 0 && <p className="muted">No missions yet. Plan one in the console, or click the map.</p>}
      <ul>
        {missions.map((m) => {
          const done = m.progress.total ? Math.round((m.progress.completed / m.progress.total) * 100) : 0;
          return (
            <li key={m.id}>
              <div className="mission-head">
                <span>
                  <strong>#{m.id}</strong> {m.name}
                </span>
                <span className={`chip chip-${m.status === 'IN_PROGRESS' ? 'EXECUTING' : m.status}`}>{m.status.replace('_', ' ')}</span>
              </div>
              <div className="mission-progress" title={`${m.progress.completed} of ${m.progress.total} steps completed`}>
                <span style={{ width: `${done}%` }} />
              </div>
              <div className="muted small-text">
                {m.progress.completed}/{m.progress.total} steps · ends at ({m.forecast.x}, {m.forecast.y}) facing {m.forecast.heading} ·
                battery {m.forecast.batteryPercent} % · by {m.createdBy}
                {canCancel && m._links.cancel && (
                  <button className="small secondary mission-cancel" onClick={() => void cancel(m)}>
                    Cancel mission
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
