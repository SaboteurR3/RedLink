import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import { RoverStream, type StreamStatus } from '../api/events';
import {
  FINAL_STATUSES,
  type Command,
  type LinkStatus,
  type Me,
  type Mission,
  type Photo,
  type PlanPreview,
  type Rover,
  type RoverEventMessage,
  type Sample,
  type Site,
  type Specs,
  type UplinkTransmission,
} from '../api/types';
import { commandsNewestFirst, dustStormActive, initialMission, missionReducer } from '../state/mission';
import type { RoverActivity } from '../scene/RoverModel';
import type { Focus } from '../scene/Scene3D';
import { ApprovalInbox } from './ApprovalInbox';
import { CommandConsole } from './CommandConsole';
import { CommandTimeline, describe } from './CommandTimeline';
import { LinkPanel } from './LinkPanel';
import { MapView } from './MapView';
import { MissionsPanel } from './MissionsPanel';
import { FirstVisitHints } from './FirstVisitHints';
import { WebhooksDialog } from './WebhooksDialog';
import { AuditDialog } from './AuditDialog';
import { PhotoStrip } from './PhotoStrip';
import { RoutePanel, type RouteState } from './RoutePanel';
import { TelemetryPanel } from './TelemetryPanel';
import { TelemetryCharts } from '../charts/TelemetryCharts';
import { Toasts, useToasts } from './Toasts';

const ACTIVITY: Record<Command['type'], RoverActivity> = {
  MOVE: 'move',
  ROTATE: 'rotate',
  TAKE_PHOTO: 'photo',
  DRILL_SAMPLE: 'drill',
  CHARGE: 'charge',
  REBOOT: null,
};

/**
 * Everything about one rover. Mounted with `key={roverId}`, so switching rovers starts clean: no
 * commands, photos or open stream of the other rover survive the switch.
 */
export function MissionControl({
  api,
  me,
  onLogout,
  roverId,
  rovers,
  onRover,
}: {
  api: ApiClient;
  me: Me;
  onLogout: () => void;
  roverId: number;
  /** All rovers, for the picker in the top bar. */
  rovers: Rover[];
  onRover: (roverId: number) => void;
}) {
  const [state, dispatch] = useReducer(missionReducer, initialMission);
  const [site, setSite] = useState<Site | null>(null);
  const [focus, setFocus] = useState<Focus | null>(null);
  const [webhooksOpen, setWebhooksOpen] = useState(false);
  const [auditOpen, setAuditOpen] = useState(false);
  /** Bumped when a mission step changes, so the missions panel reloads. */
  const [missionTick, setMissionTick] = useState(0);
  const [flashAt, setFlashAt] = useState(0);
  const [consolePreview, setConsolePreview] = useState<PlanPreview | null>(null);
  const [route, setRoute] = useState<RouteState | null>(null);
  const [link, setLink] = useState<LinkStatus | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [samples, setSamples] = useState<Sample[]>([]);
  const [planned, setPlanned] = useState<Mission | null>(null);
  const [stream, setStream] = useState<StreamStatus>('connecting');
  const [error, setError] = useState<string | null>(null);
  const [specs, setSpecs] = useState<Specs | null>(null);
  const { toasts, push: toast, dismiss } = useToasts();
  // The stream callback outlives renders: it reads the latest state through refs.
  const stateRef = useRef(state);
  stateRef.current = state;
  const linkRef = useRef(link);
  linkRef.current = link;
  // The server enforces roles on every request; this only hides what would be refused anyway.
  const canCommand = me.roles.includes('OPERATOR') || me.roles.includes('FLIGHT_DIRECTOR');

  const load = useCallback(async () => {
    const [rover, page, pending, photoPage, linkStatus, samplePage] = await Promise.all([
      api.rover(roverId),
      api.commands(roverId),
      // Waiting approvals may be older than the newest 50: load them on their own.
      api.commands(roverId, 100, 'PENDING_APPROVAL'),
      api.photos(roverId),
      api.link(),
      // Drill marks are decoration: without them the page still works.
      api.telemetry(roverId, 'SAMPLE', 200).catch(() => null),
    ]);
    if (samplePage) {
      setSamples(samplePage.content.map((r) => ({ ...(r.payload as Omit<Sample, 'roverTime'>), roverTime: r.roverTime })));
    }
    setLink(linkStatus);
    dispatch({
      type: 'loaded',
      rover,
      commands: [...page.content, ...pending.content],
    });
    setPhotos(photoPage.content);
    return rover;
  }, [api, roverId]);

  useEffect(() => {
    api
      .specs()
      .then(setSpecs)
      .catch(() => setSpecs(null)); // estimates are optional
  }, [api]);

  // Relay windows open and close on a schedule, not by an event: ask again when this one changes.
  const nextWindowChange = link?.window.nextChangeAt ?? null;
  useEffect(() => {
    if (!nextWindowChange) return;
    const timer = setTimeout(
      () =>
        api
          .link()
          .then(setLink)
          .catch(() => undefined),
      Math.max(0, Date.parse(nextWindowChange) - Date.now()) + 500,
    );
    return () => clearTimeout(timer);
  }, [api, nextWindowChange]);

  /** A command reached its end: say so, wherever the user is looking. */
  const notifyFinished = useCallback(
    (command: Command) => {
      const what = `#${command.id} ${describe(command.command)}`;
      switch (command.status) {
        case 'COMPLETED':
          toast('ok', `${what} completed`);
          break;
        case 'FAILED':
        case 'REFUSED_BY_ROVER':
          toast('bad', `${what} ${command.status === 'FAILED' ? 'failed' : 'was refused'}`, command.failure?.detail);
          break;
        default:
          toast('info', `${what} ${command.status.toLowerCase()}`);
      }
    },
    [toast],
  );

  useEffect(() => {
    load()
      .then((rover) => api.site(rover.siteId))
      .then(setSite)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable'));
  }, [api, load]);

  useEffect(() => {
    const roverStream = new RoverStream(api, roverId, {
      onEvent: (name, data) => {
        switch (name) {
          case 'rover':
            dispatch({ type: 'rover', rover: data as Rover });
            break;
          case 'command': {
            const command = data as Command;
            const before = stateRef.current.commands[command.id];
            // Only a change into a final state (a resumed stream repeats events we already showed).
            if (FINAL_STATUSES.has(command.status) && !(before && FINAL_STATUSES.has(before.status))) {
              notifyFinished(command);
            }
            if (command.mission) setMissionTick((t) => t + 1);
            dispatch({ type: 'command', command });
            break;
          }
          case 'uplink':
            dispatch({ type: 'uplink', uplink: data as UplinkTransmission });
            break;
          case 'rover-event': {
            const event = data as RoverEventMessage;
            if (event.severity !== 'INFO') {
              toast(event.severity === 'CRITICAL' ? 'bad' : 'warn', event.code.replaceAll('_', ' '), event.message);
            }
            dispatch({ type: 'rover-event', event });
            break;
          }
          case 'photo': {
            const photo = data as Photo;
            setFlashAt(Date.now());
            toast('info', `New photo (${photo.camera})`, `Taken at (${photo.x}, ${photo.y}) facing ${photo.heading}`);
            setPhotos((shown) => [photo, ...shown.filter((p) => p.id !== photo.id)]);
            break;
          }
          case 'sample': {
            const sample = data as Sample;
            toast(
              'info',
              sample.material === 'ROCK' ? 'Rock sample taken' : 'Soil sample taken',
              sample.material === 'ROCK'
                ? `Cored the rock in front of (${sample.x}, ${sample.y}) facing ${sample.heading}`
                : `Drilled the ground at (${sample.x}, ${sample.y})`,
            );
            setSamples((shown) => [sample, ...shown.filter((s) => s.commandId !== sample.commandId)]);
            break;
          }
          case 'link': {
            const next = data as LinkStatus;
            if (linkRef.current && linkRef.current.conjunction.active !== next.conjunction.active) {
              toast(
                'warn',
                next.conjunction.active ? 'Solar conjunction began' : 'Solar conjunction ended',
                next.conjunction.active ? 'Commands wait on Earth until it is over.' : 'The link is open again.',
              );
            }
            setLink(next);
            break;
          }
          case 'reset':
            // We were away too long to catch up event by event: start from a fresh snapshot.
            dispatch({ type: 'rover', rover: data as Rover });
            void load();
            break;
        }
      },
      onStatus: setStream,
      onAuthError: onLogout,
    });
    roverStream.start();
    return () => roverStream.stop();
  }, [api, roverId, load, onLogout, toast, notifyFinished]);

  const onCommand = useCallback((command: Command) => dispatch({ type: 'command', command }), []);

  /** Click on the map: plan the drive there on the server and show it. */
  const onCellClick = useCallback(
    (x: number, y: number) => {
      setRoute({ kind: 'loading', x, y });
      api
        .route(roverId, x, y)
        .then((proposal) => setRoute((r) => (r && r.x === x && r.y === y ? { kind: 'ready', x, y, route: proposal } : r)))
        .catch((e: unknown) =>
          setRoute((r) =>
            r && r.x === x && r.y === y
              ? {
                  kind: 'error',
                  x,
                  y,
                  message: e instanceof ApiError ? e.describe() : 'Mission control is not reachable',
                }
              : r,
          ),
        );
    },
    [api, roverId],
  );

  // The ghost rover shows where the planned mission ends, until all its steps have ended.
  const missionRunning = planned?.steps.some((step) => !FINAL_STATUSES.has((state.commands[step.id] ?? step).status)) ?? false;
  const forecast = planned && missionRunning ? planned.forecast : null;
  const pending = Object.values(state.commands).filter((c) => c.status === 'PENDING_APPROVAL');
  // What the rover is doing by its last report, for the 3D animations.
  const executing = Object.values(state.commands).find((c) => c.status === 'EXECUTING');
  const activity = executing ? ACTIVITY[executing.type] : null;

  return (
    <div className="app">
      <header className="topbar">
        <h1>
          <span className="logo-red">Red</span>Link <span className="muted">Mission Control</span>
        </h1>
        {rovers.length > 1 && (
          <select
            className="rover-picker"
            value={roverId}
            onChange={(e) => onRover(Number(e.target.value))}
            aria-label="Rover"
            title="Which rover to command"
          >
            {rovers.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
                {r.lastKnown.mode === 'SAFE_MODE' ? ' ⚠' : ''}
              </option>
            ))}
          </select>
        )}
        <span className={`stream stream-${stream}`} title="Live event stream (SSE)">
          ● {stream}
        </span>
        {me.roles.includes('FLIGHT_DIRECTOR') && <ApprovalInbox api={api} me={me} pending={pending} onCommand={onCommand} />}
        <button className="secondary" onClick={() => setWebhooksOpen(true)} title="Notify other systems">
          Webhooks
        </button>
        {me.roles.includes('FLIGHT_DIRECTOR') && (
          <button className="secondary" onClick={() => setAuditOpen(true)} title="Who changed what and when">
            Audit
          </button>
        )}
        <span className="user">
          {me.username} <span className="role">{me.roles.join(', ')}</span>
        </span>
        <button className="secondary" onClick={onLogout}>
          Log out
        </button>
      </header>

      {error && <p className="error banner">{error}</p>}

      <main className="layout">
        <section className="map-area">
          <FirstVisitHints />
          {site && state.rover ? (
            <MapView
              api={api}
              site={site}
              rover={state.rover}
              uplinks={state.uplinks}
              photos={photos}
              forecast={forecast}
              dustStorm={dustStormActive(state)}
              // A route to a clicked cell takes the map over from the console's preview.
              preview={route ? (route.kind === 'ready' ? route.route.preview : null) : consolePreview}
              target={route ? { x: route.x, y: route.y } : null}
              onCellClick={onCellClick}
              focus={focus}
              onClearFocus={() => setFocus(null)}
              activity={activity}
              flashAt={flashAt}
              link={link}
              samples={samples}
            />
          ) : (
            <div className="panel">Loading terrain…</div>
          )}
          {route && (
            <RoutePanel
              api={api}
              roverId={roverId}
              route={route}
              canDrive={canCommand}
              onSent={(mission, steps) => {
                steps.forEach(onCommand);
                setPlanned(mission);
                setRoute(null);
                setMissionTick((t) => t + 1);
                toast('ok', `Mission #${mission.id} sent`, mission.name);
              }}
              onClose={() => setRoute(null)}
            />
          )}
        </section>
        <aside className="side">
          {state.rover && <TelemetryPanel rover={state.rover} />}
          <TelemetryCharts api={api} roverId={roverId} rover={state.rover} />
          <LinkPanel api={api} state={state} link={link} canDirect={me.roles.includes('FLIGHT_DIRECTOR')} onLink={setLink} />
          <PhotoStrip
            api={api}
            photos={photos}
            onShowOnMap={(p) => setFocus({ x: p.x, y: p.y, heading: p.heading, camera: p.camera })}
          />
        </aside>
        <section className="commands">
          {/* Console and commands list stack on the left, so neither waits for the missions list. */}
          <div className="commands-main">
            {canCommand ? (
              <CommandConsole
                api={api}
                roverId={roverId}
                rover={state.rover}
                specs={specs}
                link={link}
                onCommand={onCommand}
                onMission={(m) => {
                  setPlanned(m);
                  setMissionTick((t) => t + 1);
                }}
                onPreview={setConsolePreview}
              />
            ) : (
              <div className="panel muted">Viewers watch only; sending commands needs the OPERATOR role.</div>
            )}
            <CommandTimeline api={api} me={me} commands={commandsNewestFirst(state)} onCommand={onCommand} />
          </div>
          <MissionsPanel api={api} roverId={roverId} refresh={missionTick} canCancel={canCommand} onCommand={onCommand} />
        </section>
      </main>
      <Toasts toasts={toasts} onDismiss={dismiss} />
      {webhooksOpen && <WebhooksDialog api={api} onClose={() => setWebhooksOpen(false)} />}
      {auditOpen && <AuditDialog api={api} onClose={() => setAuditOpen(false)} />}
    </div>
  );
}
