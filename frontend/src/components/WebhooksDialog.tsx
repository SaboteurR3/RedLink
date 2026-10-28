import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import { WEBHOOK_EVENTS, type CreatedWebhook, type Delivery, type Webhook } from '../api/types';

const EVENT_LABELS: Record<(typeof WEBHOOK_EVENTS)[number], string> = {
  'command.completed': 'A command completed',
  'command.failed': 'A command failed or was refused',
  'rover.safe_mode': 'The rover entered safe mode',
  'rover.battery_low': 'The battery dropped below 20 %',
};

/**
 * Your webhooks: other systems that RedLink notifies. The signing secret is shown once, right after
 * registering; the server never returns it again.
 */
export function WebhooksDialog({ api, onClose }: { api: ApiClient; onClose: () => void }) {
  const [hooks, setHooks] = useState<Webhook[]>([]);
  const [url, setUrl] = useState('https://');
  const [events, setEvents] = useState<string[]>(['command.completed', 'command.failed']);
  const [created, setCreated] = useState<CreatedWebhook | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [error, setError] = useState<string | null>(null);

  const explain = (e: unknown) => setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
  const load = useCallback(() => {
    api.webhooks().then(setHooks).catch(explain);
  }, [api]);
  useEffect(load, [load]);

  const register = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      setCreated(await api.createWebhook(url.trim(), events));
      load();
    } catch (e) {
      explain(e);
    }
  };

  const showDeliveries = async (id: string) => {
    if (open === id) return setOpen(null);
    setOpen(id);
    try {
      setDeliveries((await api.deliveries(id)).content as Delivery[]);
    } catch (e) {
      explain(e);
    }
  };

  const act = async (action: () => Promise<void>) => {
    setError(null);
    try {
      await action();
      load();
    } catch (e) {
      explain(e);
    }
  };

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal panel" role="dialog" aria-label="Webhooks">
        <div className="modal-head">
          <h2>Webhooks</h2>
          <button className="secondary small" onClick={onClose}>
            Close
          </button>
        </div>
        <p className="muted">RedLink calls these addresses (https, public internet only) when something happens. Each call is signed with the webhook’s secret.</p>
        {error && <p className="error">{error}</p>}

        {created && (
          <div className="secret-box">
            <strong>Secret for {created.url} — shown only now:</strong>
            <code>{created.secret}</code>
            <button className="small" onClick={() => void navigator.clipboard?.writeText(created.secret)}>
              Copy
            </button>
          </div>
        )}

        <form className="webhook-form" onSubmit={register}>
          <input value={url} onChange={(e) => setUrl(e.target.value)} aria-label="Webhook URL" placeholder="https://example.com/redlink" />
          <div className="webhook-events">
            {WEBHOOK_EVENTS.map((ev) => (
              <label key={ev}>
                <input
                  type="checkbox"
                  checked={events.includes(ev)}
                  onChange={(e) => setEvents(e.target.checked ? [...events, ev] : events.filter((x) => x !== ev))}
                />{' '}
                {EVENT_LABELS[ev]}
              </label>
            ))}
          </div>
          <button type="submit" disabled={events.length === 0}>
            Register
          </button>
        </form>

        <ul className="webhook-list">
          {hooks.length === 0 && <li className="muted">No webhooks yet.</li>}
          {hooks.map((h) => (
            <li key={h.id}>
              <div className="mission-head">
                <span>
                  {h.url} <span className="muted small-text">{h.events.join(', ')}</span>
                </span>
                <span className={`chip ${h.active ? 'chip-COMPLETED' : 'chip-FAILED'}`}>{h.active ? 'active' : 'stopped'}</span>
              </div>
              <div className="console-row">
                <button className="small secondary" onClick={() => void showDeliveries(h.id)}>
                  {open === h.id ? 'Hide deliveries' : 'Deliveries'}
                </button>
                <button className="small secondary" onClick={() => void act(() => api.pingWebhook(h.id))}>
                  Send test
                </button>
                <button className="small secondary" onClick={() => void act(() => api.deleteWebhook(h.id))}>
                  Delete
                </button>
              </div>
              {open === h.id && (
                <ul className="deliveries">
                  {deliveries.length === 0 && <li className="muted">No deliveries yet.</li>}
                  {deliveries.map((d) => (
                    <li key={d.id} className="small-text">
                      {d.event} · <strong>{d.status}</strong> · {d.attempts} attempt(s)
                      {d.lastStatusCode !== null && ` · HTTP ${d.lastStatusCode}`}
                      {d.lastError && <span className="muted"> · {d.lastError}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
