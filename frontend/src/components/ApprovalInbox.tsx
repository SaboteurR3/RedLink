import { useEffect, useRef, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { Command, Me } from '../api/types';
import { describe } from './CommandTimeline';

/**
 * A flight director's to-do list: drills waiting for approval. Other people's can be decided here
 * (the server enforces the four-eyes rule and roles again); one's own are listed as waiting.
 */
export function ApprovalInbox({
  api,
  me,
  pending,
  onCommand,
}: {
  api: ApiClient;
  me: Me;
  pending: Command[];
  onCommand: (c: Command) => void;
}) {
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const mine = pending.filter((c) => c.createdBy === me.username);
  const toDecide = pending.filter((c) => c.createdBy !== me.username);

  // Close when clicking elsewhere.
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (box.current && !box.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);

  const decide = async (command: Command, decision: 'approve' | 'reject') => {
    const link = command._links[decision];
    if (!link) return;
    setError(null);
    try {
      let body: unknown;
      if (decision === 'reject') {
        const reason = window.prompt(`Reject ${describe(command.command)} (#${command.id})? Reason for ${command.createdBy}:`);
        if (reason === null) return;
        body = { reason: reason.trim() || null };
      }
      onCommand(await api.follow<Command>(link, body));
    } catch (e) {
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    }
  };

  return (
    <div className="inbox" ref={box}>
      <button
        className={`inbox-badge ${toDecide.length ? 'has-items' : 'secondary'}`}
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        title="Commands waiting for approval"
      >
        ⚑ {toDecide.length} to approve
      </button>
      {open && (
        <div className="inbox-panel panel">
          <h2>Waiting for approval</h2>
          {error && <p className="error">{error}</p>}
          {toDecide.length === 0 && <p className="muted">Nothing for you to decide.</p>}
          <ul>
            {toDecide.map((c) => (
              <li key={c.id}>
                <span>
                  #{c.id} {describe(c.command)} <span className="muted">by {c.createdBy}</span>
                </span>
                <span className="inbox-actions">
                  {c._links.approve && (
                    <button className="small" onClick={() => void decide(c, 'approve')}>
                      Approve
                    </button>
                  )}
                  {c._links.reject && (
                    <button className="small secondary" onClick={() => void decide(c, 'reject')}>
                      Reject
                    </button>
                  )}
                </span>
              </li>
            ))}
          </ul>
          {mine.length > 0 && (
            <>
              <h3>Yours, waiting for another director</h3>
              <ul>
                {mine.map((c) => (
                  <li key={c.id} className="muted">
                    #{c.id} {describe(c.command)}
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  );
}
