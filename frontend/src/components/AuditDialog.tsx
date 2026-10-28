import { useEffect, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { AuditAction, AuditEntry } from '../api/types';

const ACTIONS: AuditAction[] = [
  'COMMAND_SUBMITTED',
  'COMMAND_CANCELLED',
  'COMMAND_APPROVED',
  'COMMAND_REJECTED',
  'MISSION_SUBMITTED',
  'MISSION_CANCELLED',
  'CONJUNCTION_DECLARED',
  'CONJUNCTION_ENDED',
  'WEBHOOK_REGISTERED',
  'WEBHOOK_DELETED',
];

/**
 * Who changed what and when (flight directors). Paging follows the server's RFC 8288 `Link`
 * header: the Newer/Older buttons are its `prev`/`next` targets.
 */
export function AuditDialog({ api, onClose }: { api: ApiClient; onClose: () => void }) {
  const [actor, setActor] = useState('');
  const [action, setAction] = useState<AuditAction | ''>('');
  const [path, setPath] = useState<string | null>(null);
  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [links, setLinks] = useState<Record<string, string>>({});
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // A filter change starts again on the first page.
  useEffect(() => {
    const query = new URLSearchParams({ size: '15' });
    if (actor.trim()) query.set('actor', actor.trim());
    if (action) query.set('action', action);
    setPath(`/api/v1/audit?${query}`);
  }, [actor, action]);

  useEffect(() => {
    if (!path) return;
    let cancelled = false;
    api
      .audit(path)
      .then(({ page, links: next }) => {
        if (cancelled) return;
        setEntries(page.content);
        setTotal(page.page.totalElements);
        setLinks(next);
        setError(null);
      })
      .catch((e: unknown) => !cancelled && setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable'));
    return () => {
      cancelled = true;
    };
  }, [api, path]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal panel audit" role="dialog" aria-label="Audit log">
        <div className="modal-head">
          <h2>Audit log</h2>
          <button className="secondary small" onClick={onClose}>
            Close
          </button>
        </div>
        <div className="console-row">
          <input placeholder="User" value={actor} onChange={(e) => setActor(e.target.value)} aria-label="Filter by user" />
          <select value={action} onChange={(e) => setAction(e.target.value as AuditAction | '')} aria-label="Filter by action">
            <option value="">All actions</option>
            {ACTIONS.map((a) => (
              <option key={a} value={a}>
                {a.replaceAll('_', ' ').toLowerCase()}
              </option>
            ))}
          </select>
        </div>
        {error && <p className="error">{error}</p>}
        <table className="audit-table">
          <thead>
            <tr>
              <th>When</th>
              <th>Who</th>
              <th>What</th>
              <th>On</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.id}>
                <td>{new Date(entry.occurredAt).toLocaleString()}</td>
                <td>{entry.actor}</td>
                <td>{entry.action.replaceAll('_', ' ').toLowerCase()}</td>
                <td>
                  <code>{entry.target}</code>
                </td>
                <td className="muted">{entry.detail}</td>
              </tr>
            ))}
            {entries.length === 0 && !error && (
              <tr>
                <td colSpan={5} className="muted">
                  Nothing recorded yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
        <div className="console-row">
          <button className="secondary small" disabled={!links.prev} onClick={() => links.prev && setPath(links.prev)}>
            ◂ Newer
          </button>
          <span className="muted">{total} entries</span>
          <button className="secondary small" disabled={!links.next} onClick={() => links.next && setPath(links.next)}>
            Older ▸
          </button>
        </div>
      </div>
    </div>
  );
}
