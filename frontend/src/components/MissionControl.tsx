import { useCallback, useEffect, useReducer, useState } from 'react';
import { ApiError, type ApiClient } from '../api/client';
import { RoverStream, type StreamStatus } from '../api/events';
import { FINAL_STATUSES, type Command, type LinkStatus, type Me, type Mission, type Photo, type Rover, type RoverEventMessage, type Site, type UplinkTransmission } from '../api/types';
import { commandsNewestFirst, dustStormActive, initialMission, missionReducer } from '../state/mission';
import { CommandConsole } from './CommandConsole';
import { CommandTimeline } from './CommandTimeline';
import { LinkPanel } from './LinkPanel';
import { MapView } from './MapView';
import { PhotoStrip } from './PhotoStrip';
import { TelemetryPanel } from './TelemetryPanel';

const ROVER_ID = 1;

export function MissionControl({ api, me, onLogout }: { api: ApiClient; me: Me; onLogout: () => void }) {
  const [state, dispatch] = useReducer(missionReducer, initialMission);
  const [site, setSite] = useState<Site | null>(null);
  const [link, setLink] = useState<LinkStatus | null>(null);
  const [photos, setPhotos] = useState<Photo[]>([]);
  const [planned, setPlanned] = useState<Mission | null>(null);
  const [stream, setStream] = useState<StreamStatus>('connecting');
  const [error, setError] = useState<string | null>(null);
  // The server enforces roles on every request; this only hides what would be refused anyway.
  const canCommand = me.roles.includes('OPERATOR') || me.roles.includes('FLIGHT_DIRECTOR');

  const load = useCallback(async () => {
    const [rover, page, photoPage, linkStatus] = await Promise.all([
      api.rover(ROVER_ID),
      api.commands(ROVER_ID),
      api.photos(ROVER_ID),
      api.link(),
    ]);
    setLink(linkStatus);
    dispatch({ type: 'loaded', rover, commands: page.content });
    setPhotos(photoPage.content);
    return rover;
  }, [api]);

  useEffect(() => {
    load()
      .then((rover) => api.site(rover.siteId))
      .then(setSite)
      .catch((e: unknown) => setError(e instanceof ApiError ? e.describe() : 'Mission control is not reachable'));
  }, [api, load]);

  useEffect(() => {
    const roverStream = new RoverStream(api, ROVER_ID, {
      onEvent: (name, data) => {
        switch (name) {
          case 'rover':
            dispatch({ type: 'rover', rover: data as Rover });
            break;
          case 'command':
            dispatch({ type: 'command', command: data as Command });
            break;
          case 'uplink':
            dispatch({ type: 'uplink', uplink: data as UplinkTransmission });
            break;
          case 'rover-event':
            dispatch({ type: 'rover-event', event: data as RoverEventMessage });
            break;
          case 'photo': {
            const photo = data as Photo;
            setPhotos((shown) => [photo, ...shown.filter((p) => p.id !== photo.id)]);
            break;
          }
          case 'link':
            setLink(data as LinkStatus);
            break;
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
  }, [api, load, onLogout]);

  const onCommand = useCallback((command: Command) => dispatch({ type: 'command', command }), []);

  // The ghost rover shows where the planned mission ends, until all its steps have ended.
  const missionRunning = planned?.steps.some((step) => !FINAL_STATUSES.has((state.commands[step.id] ?? step).status)) ?? false;
  const forecast = planned && missionRunning ? planned.forecast : null;

  return (
    <div className="app">
      <header className="topbar">
        <h1>
          <span className="logo-red">Red</span>Link <span className="muted">Mission Control</span>
        </h1>
        <span className={`stream stream-${stream}`} title="Live event stream (SSE)">
          ● {stream}
        </span>
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
          {site && state.rover ? (
            <MapView api={api} site={site} rover={state.rover} uplinks={state.uplinks} photos={photos} forecast={forecast} dustStorm={dustStormActive(state)} />
          ) : (
            <div className="panel">Loading terrain…</div>
          )}
        </section>
        <aside className="side">
          {state.rover && <TelemetryPanel rover={state.rover} />}
          <LinkPanel api={api} state={state} link={link} canDirect={me.roles.includes('FLIGHT_DIRECTOR')} onLink={setLink} />
          <PhotoStrip api={api} photos={photos} />
        </aside>
        <section className="commands">
          {canCommand ? (
            <CommandConsole api={api} roverId={ROVER_ID} onCommand={onCommand} onMission={setPlanned} />
          ) : (
            <div className="panel muted">Viewers watch only; sending commands needs the OPERATOR role.</div>
          )}
          <CommandTimeline api={api} me={me} commands={commandsNewestFirst(state)} onCommand={onCommand} />
        </section>
      </main>
    </div>
  );
}
