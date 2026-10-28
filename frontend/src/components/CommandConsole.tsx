import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, NetworkError, type ApiClient } from '../api/client';
import {
  COMMAND_TYPES,
  type Camera,
  type Command,
  type CommandType,
  type Heading,
  type LinkStatus,
  type Mission,
  type PlanPreview,
  type Problem,
  type Rover,
  type RoverCommand,
  type Specs,
} from '../api/types';
import { estimate, formatBattery } from '../console/estimate';
import { describe } from './CommandTimeline';
import { solPhase, sunlight, untilSunriseMs } from '../scene/sol';

const MAX_STEPS = 50;

type Result = { ok: boolean; text: string; blocking?: NonNullable<Problem['blockingCommands']> };

function explain(e: unknown): Result {
  if (e instanceof ApiError) return { ok: false, text: e.describe(), blocking: e.problem.blockingCommands };
  return { ok: false, text: e instanceof NetworkError ? 'Mission control is not reachable' : String(e) };
}

/**
 * Forms for the command dictionary, with its limits. A command is either sent at once or added to
 * a mission plan, which the server checks as a whole before anything is queued. Estimates and
 * warnings come from the rover's published spec sheet and Earth's last knowledge; the server and
 * the rover still decide.
 */
export function CommandConsole({
  api,
  roverId,
  rover,
  specs,
  link,
  onCommand,
  onMission,
  onPreview,
}: {
  api: ApiClient;
  roverId: number;
  rover: Rover | null;
  specs: Specs | null;
  link: LinkStatus | null;
  onCommand: (c: Command) => void;
  onMission?: (m: Mission) => void;
  /** The dry run of what is being prepared (the plan, or else the current command), for the map. */
  onPreview?: (preview: PlanPreview | null) => void;
}) {
  const [chosen, setType] = useState<CommandType>('MOVE');
  const [distance, setDistance] = useState(3);
  const [heading, setHeading] = useState<Heading>('EAST');
  const [camera, setCamera] = useState<Camera>('NAVCAM');
  const [minutes, setMinutes] = useState(10);
  const [plan, setPlan] = useState<RoverCommand[]>([]);
  const [planName, setPlanName] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<Result | null>(null);
  const [preview, setPreview] = useState<PlanPreview | null>(null);

  const safeMode = rover?.lastKnown.mode === 'SAFE_MODE';
  // In safe mode the rover only accepts REBOOT: don't offer anything else.
  const type: CommandType = safeMode ? 'REBOOT' : chosen;

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

  const guess = rover && specs ? estimate(build(), rover.lastKnown, specs, link) : null;

  // Dry run of the plan (or the single command) after the user stops changing it, for the map.
  const previewKey = JSON.stringify(plan.length ? plan : [build()]);
  const lastReport = rover?.lastKnown.reportedAt;
  useEffect(() => {
    if (!onPreview) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      api
        .preview(roverId, JSON.parse(previewKey) as RoverCommand[])
        .then((p) => {
          if (cancelled) return;
          setPreview(p);
          onPreview(p);
        })
        .catch(() => {
          if (cancelled) return;
          setPreview(null);
          onPreview(null);
        });
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
    // A new rover report changes the start of the plan.
  }, [api, roverId, previewKey, lastReport, onPreview]);

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
      setResult(explain(e));
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
      setResult(explain(e));
    } finally {
      setBusy(false);
    }
  };

  /** Withdraws a blocking command by following its own cancel link (the server says if it's possible). */
  const cancelBlocking = async (commandId: number) => {
    try {
      const command = await api.command(commandId);
      if (!command._links.cancel) {
        setResult((r) => r && { ...r, text: `${r.text} — #${commandId} can no longer be cancelled` });
        return;
      }
      const cancelled = await api.follow<Command>(command._links.cancel);
      onCommand(cancelled);
      setResult((r) => r && { ...r, blocking: r.blocking?.filter((b) => b.id !== commandId) });
    } catch (e) {
      setResult(explain(e));
    }
  };

  return (
    <form className="panel console" onSubmit={submit}>
      <h2>Command console</h2>
      {safeMode && (
        <p className="banner-safe">⚠ The rover is in safe mode: it only accepts REBOOT until Earth has looked at the problem.</p>
      )}
      <div className="console-row">
        <select value={type} onChange={(e) => setType(e.target.value as CommandType)} aria-label="Command" disabled={safeMode}>
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
        <button
          type="button"
          className="secondary"
          disabled={busy || safeMode || plan.length >= MAX_STEPS}
          onClick={() => setPlan([...plan, build()])}
        >
          Add to plan
        </button>
      </div>

      {guess && (
        <div className="estimate">
          <span>⏱ {guess.marsSeconds === null ? `${minutes} simulated min` : `${guess.marsSeconds} s on Mars`}</span>
          <span>🔋 {formatBattery(guess)}</span>
          {guess.storage > 0 && <span>💾 +{guess.storage} %</span>}
          {guess.seeResultSeconds !== null && <span className="muted">result on screen in about {guess.seeResultSeconds} s</span>}
        </div>
      )}
      {type === 'CHARGE' && <SunHint />}
      {preview && (
        <p className={`preview-line ${preview.feasible ? 'ok' : 'error'}`}>
          {preview.feasible
            ? `Path checked: ends at (${preview.end.x}, ${preview.end.y}) facing ${preview.end.heading}, battery ${preview.end.batteryPercent} %`
            : `Step ${preview.problem?.step} would fail: ${preview.problem?.reason}`}
          {preview.roverBusy && ' (unfinished commands may change the start)'}
        </p>
      )}
      {guess?.warnings.map((w) => (
        <p key={w} className="warn small-text">
          {w}
        </p>
      ))}

      {plan.length > 0 && (
        <div className="plan">
          <h3>Mission plan</h3>
          <ol>
            {plan.map((step, i) => (
              <li key={i}>
                {describe(step)}{' '}
                <button
                  type="button"
                  className="secondary small"
                  aria-label={`Remove step ${i + 1}`}
                  onClick={() => setPlan(plan.filter((_, j) => j !== i))}
                >
                  ✕
                </button>
              </li>
            ))}
          </ol>
          <div className="console-row">
            <input
              placeholder="Mission name"
              maxLength={100}
              value={planName}
              onChange={(e) => setPlanName(e.target.value)}
              aria-label="Mission name"
            />
            <button type="button" disabled={busy} onClick={() => void submitMission()}>
              Send mission ({plan.length} {plan.length === 1 ? 'step' : 'steps'})
            </button>
            <button type="button" className="secondary" disabled={busy} onClick={() => setPlan([])}>
              Clear
            </button>
          </div>
          <p className="muted">
            The whole plan is checked against the rover’s last known state and the terrain first; it needs a rover with no
            unfinished commands.
          </p>
        </div>
      )}
      {result && <p className={result.ok ? 'ok' : 'error'}>{result.text}</p>}
      {result?.blocking && result.blocking.length > 0 && (
        <div className="blocking">
          <p className="muted">These commands are still unfinished:</p>
          <ul>
            {result.blocking.map((b) => (
              <li key={b.id}>
                #{b.id} {b.type.replaceAll('_', ' ')} <span className="chip">{b.status.replaceAll('_', ' ')}</span>{' '}
                {b.cancellable ? (
                  <button type="button" className="small secondary" onClick={() => void cancelBlocking(b.id)}>
                    Cancel
                  </button>
                ) : (
                  <span className="muted">on its way, wait for it</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </form>
  );
}

/**
 * The rover charges from the sun: at night a CHARGE gains nothing, in a low sun only part of the
 * rate. Uses the real Mars clock (the rover's), not a `?sol=` pinned for screenshots.
 */
function SunHint() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(timer);
  }, []);
  const phase = solPhase(now);
  const sun = sunlight(phase);
  if (sun >= 0.5) return null;
  return (
    <p className="preview-line warn">
      {sun === 0
        ? `☾ Night at the site: charging gains nothing until sunrise in about ${Math.ceil(untilSunriseMs(phase) / 1000)} s`
        : `🌅 Low sun: charging gains about ${Math.round(sun * 100)} % of the full rate`}
    </p>
  );
}
