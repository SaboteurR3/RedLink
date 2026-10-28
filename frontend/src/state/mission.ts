import type { Command, Rover, RoverEventMessage, UplinkTransmission } from '../api/types';

/** What the screen shows about one rover: always Earth's (delayed) knowledge, never live truth. */
export type MissionState = {
  rover: Rover | null;
  commands: Record<number, Command>;
  /** Signals on their way to Mars, newest first. */
  uplinks: UplinkTransmission[];
  /** Rover events, newest first. */
  log: RoverEventMessage[];
};

export type MissionAction =
  | { type: 'loaded'; rover: Rover; commands: Command[] }
  | { type: 'rover'; rover: Rover }
  | { type: 'command'; command: Command }
  | { type: 'uplink'; uplink: UplinkTransmission }
  | { type: 'rover-event'; event: RoverEventMessage };

export const initialMission: MissionState = { rover: null, commands: {}, uplinks: [], log: [] };

const MAX_LOG = 50;
const MAX_UPLINKS = 20;

export function missionReducer(state: MissionState, action: MissionAction): MissionState {
  switch (action.type) {
    case 'loaded': {
      const commands: Record<number, Command> = {};
      for (const command of action.commands) commands[command.id] = command;
      return { ...state, rover: action.rover, commands };
    }
    case 'rover':
      return { ...state, rover: action.rover };
    case 'command': {
      const current = state.commands[action.command.id];
      // The initial page load and the stream can overlap: keep whichever version is newer.
      if (current && current.updatedAt > action.command.updatedAt) return state;
      return { ...state, commands: { ...state.commands, [action.command.id]: action.command } };
    }
    case 'uplink':
      return { ...state, uplinks: [action.uplink, ...state.uplinks].slice(0, MAX_UPLINKS) };
    case 'rover-event':
      return { ...state, log: [action.event, ...state.log].slice(0, MAX_LOG) };
  }
}

/** Newest first, by id (ids follow creation order). */
export function commandsNewestFirst(state: MissionState): Command[] {
  return Object.values(state.commands).sort((a, b) => b.id - a.id);
}

/** Signals still travelling at `now`. */
export function signalsInFlight(state: MissionState, now: number): UplinkTransmission[] {
  return state.uplinks.filter((uplink) => Date.parse(uplink.arrivesAt) > now);
}

/** True while the rover's latest dust-storm event says one started (the log is newest first). */
export function dustStormActive(state: MissionState): boolean {
  const latest = state.log.find((event) => event.code === 'DUST_STORM_STARTED' || event.code === 'DUST_STORM_ENDED');
  return latest?.code === 'DUST_STORM_STARTED';
}
