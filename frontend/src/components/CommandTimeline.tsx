import { useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import type { Command, Link, Me, RoverCommand } from '../api/types';

export function describe(command: RoverCommand): string {
  switch (command.type) {
    case 'MOVE':
      return `MOVE ${command.distanceMeters} m`;
    case 'ROTATE':
      return `ROTATE → ${command.heading}`;
    case 'TAKE_PHOTO':
      return `PHOTO ${command.camera}`;
    case 'CHARGE':
      return `CHARGE ${command.minutes} min`;
    case 'DRILL_SAMPLE':
      return 'DRILL SAMPLE';
    case 'REBOOT':
      return 'REBOOT';
  }
}

/**
 * Every command with its live status. Action buttons exist only when the server put the matching
 * link into the command (HATEOAS): the client never re-implements the lifecycle rules. Roles only
 * decide which of those offered actions this user sees; the server checks them again anyway.
 */
export function CommandTimeline({
  api,
  me,
  commands,
  onCommand,
}: {
  api: ApiClient;
  me: Me;
  commands: Command[];
  onCommand: (c: Command) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const isOperator = me.roles.includes('OPERATOR') || me.roles.includes('FLIGHT_DIRECTOR');
  const isDirector = me.roles.includes('FLIGHT_DIRECTOR');

  const act = async (link: Link, body?: unknown) => {
    setError(null);
    try {
      onCommand(await api.follow<Command>(link, body));
    } catch (e) {
      setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable');
    }
  };

  const reject = (command: Command) => {
    const reason = window.prompt(`Reject ${describe(command.command)} (#${command.id})? Reason for ${command.createdBy}:`);
    if (reason !== null && command._links.reject) {
      void act(command._links.reject, { reason: reason.trim() || null });
    }
  };

  return (
    <div className="panel timeline">
      <h2>Commands</h2>
      {error && <p className="error">{error}</p>}
      {commands.length === 0 && <p className="muted">No commands yet.</p>}
      <ol>
        {commands.map((command) => {
          // Four-eyes rule: the server refuses your own approval; don't even offer it.
          const canDecide = isDirector && command.createdBy !== me.username;
          return (
            <li key={command.id} className={`cmd status-${command.status}`}>
              <span className="cmd-id">#{command.id}</span>
              <span className="cmd-what">{describe(command.command)}</span>
              {command.mission && (
                <span className="chip chip-mission" title={`Step ${command.mission.step} of mission #${command.mission.id}`}>
                  M{command.mission.id}·{command.mission.step}
                </span>
              )}
              <span className={`chip chip-${command.status}`}>{command.status.replaceAll('_', ' ')}</span>
              <span className="cmd-seq muted">{command.sequenceNo !== null ? `seq ${command.sequenceNo}` : ''}</span>
              <span className="cmd-by muted">by {command.createdBy}</span>
              {command.status === 'EXECUTING' && (
                <span className="progress" title={`${command.progressPercent} %`}>
                  <span style={{ width: `${command.progressPercent}%` }} />
                </span>
              )}
              {canDecide && command._links.approve && (
                <button className="small" onClick={() => void act(command._links.approve!)}>
                  Approve
                </button>
              )}
              {canDecide && command._links.reject && (
                <button className="secondary small" onClick={() => reject(command)}>
                  Reject
                </button>
              )}
              {isOperator && command._links.cancel && (
                <button className="secondary small" onClick={() => void act(command._links.cancel!)}>
                  Cancel
                </button>
              )}
              {command.status === 'PENDING_APPROVAL' && !canDecide && (
                <span className="cmd-note muted">waiting for another flight director</span>
              )}
              {command.approval && command.approval.state !== 'PENDING' && command.approval.decidedBy && (
                <span className="cmd-note muted">
                  {command.approval.state.toLowerCase()} by {command.approval.decidedBy}
                  {command.approval.reason ? `: “${command.approval.reason}”` : ''}
                </span>
              )}
              {command.failure && (
                <span className="cmd-failure">
                  {command.failure.code}: {command.failure.detail}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </div>
  );
}
