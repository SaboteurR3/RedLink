import { useEffect, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { Me, Rover } from '../api/types';
import { MissionControl } from './MissionControl';

const STORAGE_KEY = 'redlink.rover';

function savedRover(): number | null {
  try {
    const value = Number(localStorage.getItem(STORAGE_KEY));
    return Number.isInteger(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

/**
 * Which rover the console shows. Remembered per browser; a rover that no longer exists falls back
 * to the first one. Each rover gets a fresh console (`key`), so nothing of the other one leaks in.
 */
export function RoverSelection({ api, me, onLogout }: { api: ApiClient; me: Me; onLogout: () => void }) {
  const [rovers, setRovers] = useState<Rover[] | null>(null);
  const [roverId, setRoverId] = useState<number | null>(savedRover);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .rovers()
      .then((page) => setRovers(page.content))
      .catch((e: unknown) => setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable'));
  }, [api]);

  const choose = (id: number) => {
    setRoverId(id);
    try {
      localStorage.setItem(STORAGE_KEY, String(id));
    } catch {
      // private window: the choice just isn't remembered
    }
  };

  if (error) return <div className="splash error">{error}</div>;
  if (!rovers) return <div className="splash">Connecting to mission control…</div>;
  if (rovers.length === 0) return <div className="splash">No rovers are registered.</div>;
  const current = rovers.find((r) => r.id === roverId) ?? rovers[0]!;
  return (
    <MissionControl key={current.id} api={api} me={me} onLogout={onLogout} roverId={current.id} rovers={rovers} onRover={choose} />
  );
}
