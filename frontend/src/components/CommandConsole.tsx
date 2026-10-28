import { useState, type FormEvent } from 'react';
import { ApiError, NetworkError, type ApiClient } from '../api/client';
import { COMMAND_TYPES, type Camera, type Command, type CommandType, type Heading, type Mission, type RoverCommand } from '../api/types';
import { describe } from './CommandTimeline';

const HELP: Record<CommandType, string> = {
  MOVE: '1 s per meter · −1 % battery per meter (−2 % on sand)',
  ROTATE: '2 s per 90° turn, the shortest way',
  TAKE_PHOTO: '3 s · +2 % storage',
  DRILL_SAMPLE: '15 s · needs ≥ 30 % battery · waits for a flight director’s approval',
  CHARGE: '+1 % battery per simulated minute',
  REBOOT: '5 s · leaves safe mode',
};

const MAX_STEPS = 50;

function explain(e: unknown): string {
  return e instanceof ApiError ? e.describe() : e instanceof NetworkError ? 'Mission control is not reachable' : String(e);
}

/**
 * Forms for the command dictionary, with its limits. A command is either sent at once or added to
 * a mission plan, which the server checks as a whole before anything is queued.
 */
export function CommandConsole({
  api,
  roverId,
  onCommand,
  onMission,
}: {
  api: ApiClient;
  roverId: number;
  onCommand: (c: Command) => void;
  onMission?: (m: Mission) => void;
}) {
  const [type, setType] = useState<CommandType>('MOVE');
  const [distance, setDistance] = useState(3);
  const [heading, setHeading] = useState<Heading>('EAST');
  const [camera, setCamera] = useState<Camera>('NAVCAM');
  const [minutes, setMinutes] = useState(10);
  const [plan, setPlan] = useState<RoverCommand[]>([]);
  const [planName, setPlanName] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  const build = (): RoverCommand => {
    switch (type) {
      case 'MOVE':
        return { type, distanceMeters: distance };
      case 'ROTATE':
        return { type, heading };
      case 'TAKE_PHOTO':
        return { type, camera };
      case 'CHARGE':
        return { type, minutes };
      case 'DRILL_SAMPLE':
      case 'REBOOT':
        return { type };
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setResult(null);
    try {
      const { command, replayed } = await api.submitCommand(roverId, build());
      onCommand(command);
      setResult({
        ok: true,
        text: `Command #${command.id} ${command.status}${replayed ? ' (a retry: the server replayed its first answer)' : ''}`,
      });
    } catch (e) {
      setResult({ ok: false, text: explain(e) });
    } finally {
      setBusy(false);
    }
  };

  const submitMission = async () => {
    setBusy(true);
    setResult(null);
    try {
      const { mission } = await api.submitMission(roverId, { name: planName.trim() || 'Mission', steps: plan });
      mission.steps.forEach(onCommand);
      onMission?.(mission);
      const f = mission.forecast;
      setResult({
        ok: true,
        text: `Mission #${mission.id} ${mission.status}: ${mission.progress.total} steps. Forecast: (${f.x}, ${f.y}) facing ${f.heading}, battery ${f.batteryPercent} %, storage ${f.storageUsedPercent} %`,
      });
      setPlan([]);
      setPlanName('');
    } catch (e) {
      setResult({ ok: false, text: explain(e) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="panel console" onSubmit={submit}>
      <h2>Command console</h2>
      <div className="console-row">
        <select value={type} onChange={(e) => setType(e.target.value as CommandType)} aria-label="Command">
          {COMMAND_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        {type === 'MOVE' && (
          <label>
            meters
            <input type="number" min={1} max={20} value={distance} onChange={(e) => setDistance(Number(e.target.value))} />
          </label>
        )}
        {type === 'ROTATE' && (
          <select value={heading} onChange={(e) => setHeading(e.target.value as Heading)} aria-label="Heading">
            {(['NORTH', 'EAST', 'SOUTH', 'WEST'] as const).map((h) => (
              <option key={h}>{h}</option>
            ))}
          </select>
        )}
        {type === 'TAKE_PHOTO' && (
          <select value={camera} onChange={(e) => setCamera(e.target.value as Camera)} aria-label="Camera">
            <option>NAVCAM</option>
            <option>MASTCAM</option>
          </select>
        )}
        {type === 'CHARGE' && (
          <label>
            minutes
            <input type="number" min={1} max={120} value={minutes} onChange={(e) => setMinutes(Number(e.target.value))} />
          </label>
        )}
        <button type="submit" disabled={busy}>
          {busy ? 'Sending…' : 'Send to rover'}
        </button>
        <button type="button" className="secondary" disabled={busy || plan.length >= MAX_STEPS} onClick={() => setPlan([...plan, build()])}>
          Add to plan
        </button>
      </div>
      <p className="muted">{HELP[type]}</p>

      {plan.length > 0 && (
        <div className="plan">
          <h3>Mission plan</h3>
          <ol>
            {plan.map((step, i) => (
              <li key={i}>
                {describe(step)}{' '}
                <button type="button" className="secondary small" aria-label={`Remove step ${i + 1}`} onClick={() => setPlan(plan.filter((_, j) => j !== i))}>
                  ✕
                </button>
              </li>
            ))}
          </ol>
          <div className="console-row">
            <input placeholder="Mission name" maxLength={100} value={planName} onChange={(e) => setPlanName(e.target.value)} aria-label="Mission name" />
            <button type="button" disabled={busy} onClick={() => void submitMission()}>
              Send mission ({plan.length} {plan.length === 1 ? 'step' : 'steps'})
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setPlan([])}>
              Clear
            </button>
          </div>
          <p className="muted">The whole plan is checked against the rover’s last known state and the terrain first; it needs a rover with no unfinished commands.</p>
        </div>
      )}
      {result && <p className={result.ok ? 'ok' : 'error'}>{result.text}</p>}
    </form>
  );
}
