import { useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { LinkStatus } from '../api/types';
import { useNow } from '../hooks/useNow';
import { signalsInFlight, type MissionState } from '../state/mission';

/** The link itself (light time, relay window, conjunction), signals on their way, and rover events. */
export function LinkPanel({
  api,
  state,
  link,
  canDirect,
  onLink,
}: {
  api: ApiClient;
  state: MissionState;
  link: LinkStatus | null;
  /** Flight directors may start and end a solar conjunction. */
  canDirect: boolean;
  onLink: (link: LinkStatus) => void;
}) {
  const now = useNow(250);
  const inFlight = signalsInFlight(state, now);
  const [error, setError] = useState<string | null>(null);

  const toggleConjunction = async (active: boolean) => {
    setError(null);
    try {
      // Conditional: if another director changed it meanwhile, the server says 412 instead of overwriting.
      const { etag } = await api.conjunction();
      await api.setConjunction(active, etag);
      onLink(await api.link());
    } catch (e) {
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    }
  };

  const nextChange = link?.window.nextChangeAt ? Math.max(0, Math.ceil((Date.parse(link.window.nextChangeAt) - now) / 1000)) : null;

  return (
    <div className="panel link">
      <h2>Deep-space link</h2>
      {link && (
        <div className="link-status">
          {link.conjunction.active && (
            <p className="banner-conjunction">
              ☀ Solar conjunction{link.conjunction.changedBy ? ` (declared by ${link.conjunction.changedBy})` : ''}: commands
              wait on Earth, telemetry waits on Mars.
            </p>
          )}
          <p className="muted">
            One-way light time {link.oneWayDelaySeconds} s
            {link.window.enabled && (
              <>
                {' · '}relay window{' '}
                <span className={link.window.open ? 'ok' : 'warn'}>{link.window.open ? 'open' : 'closed'}</span>
                {nextChange !== null && ` (${link.window.open ? 'closes' : 'opens'} in ${nextChange} s)`}
              </>
            )}
          </p>
          {canDirect && (
            <button className="secondary small" onClick={() => void toggleConjunction(!link.conjunction.active)}>
              {link.conjunction.active ? 'End conjunction' : 'Declare conjunction'}
            </button>
          )}
          {error && <p className="error">{error}</p>}
        </div>
      )}
      {inFlight.length === 0 ? (
        <p className="muted">No signal in flight.</p>
      ) : (
        <ul className="signals">
          {inFlight.map((signal) => {
            const start = Date.parse(signal.transmittedAt);
            const end = Date.parse(signal.arrivesAt);
            const done = Math.min(100, Math.max(0, ((now - start) / (end - start)) * 100));
            return (
              <li key={signal.commandId}>
                <span>
                  Command #{signal.commandId} (seq {signal.sequenceNo}) reaches Mars in {Math.ceil((end - now) / 1000)} s
                </span>
                <span className="beam">
                  <span style={{ width: `${done}%` }} />
                </span>
              </li>
            );
          })}
        </ul>
      )}
      <h3>Rover events</h3>
      {state.log.length === 0 ? (
        <p className="muted">None yet.</p>
      ) : (
        <ul className="events">
          {state.log.map((event, i) => (
            <li key={`${event.roverTime}-${i}`} className={`sev-${event.severity}`}>
              <strong>{event.code}</strong> {event.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
