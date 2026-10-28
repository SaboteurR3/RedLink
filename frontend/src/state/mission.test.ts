import { describe, expect, it } from 'vitest';
import type { Command } from '../api/types';
import { commandsNewestFirst, dustStormActive, initialMission, missionReducer, signalsInFlight } from './mission';

const command = (id: number, status: Command['status'], updatedAt: string): Command => ({
  id,
  roverId: 1,
  type: 'MOVE',
  command: { type: 'MOVE', distanceMeters: 3 },
  status,
  progressPercent: 0,
  sequenceNo: null,
  createdBy: 'operator',
  createdAt: '2026-10-27T10:00:00Z',
  updatedAt,
  transmittedAt: null,
  finishedAt: null,
  failure: null,
  approval: null,
  mission: null,
  _links: { self: { href: `/api/v1/commands/${id}` }, rover: { href: '/api/v1/rovers/1' } },
});

describe('missionReducer', () => {
  it('a newer version of a command replaces the older one', () => {
    let state = missionReducer(initialMission, { type: 'command', command: command(1, 'QUEUED', '2026-10-27T10:00:00Z') });
    state = missionReducer(state, { type: 'command', command: command(1, 'TRANSMITTED', '2026-10-27T10:00:05Z') });

    expect(state.commands[1]?.status).toBe('TRANSMITTED');
  });

  it('an older version arriving late (page load racing the stream) is ignored', () => {
    let state = missionReducer(initialMission, { type: 'command', command: command(1, 'COMPLETED', '2026-10-27T10:00:30Z') });
    state = missionReducer(state, { type: 'command', command: command(1, 'QUEUED', '2026-10-27T10:00:00Z') });

    expect(state.commands[1]?.status).toBe('COMPLETED');
  });

  it('lists commands newest first', () => {
    let state = initialMission;
    for (const id of [3, 1, 2]) {
      state = missionReducer(state, { type: 'command', command: command(id, 'QUEUED', '2026-10-27T10:00:00Z') });
    }

    expect(commandsNewestFirst(state).map((c) => c.id)).toEqual([3, 2, 1]);
  });

  it('a signal is in flight until its arrival time', () => {
    const state = missionReducer(initialMission, {
      type: 'uplink',
      uplink: { commandId: 1, sequenceNo: 1, transmittedAt: '2026-10-27T10:00:00Z', arrivesAt: '2026-10-27T10:00:10Z' },
    });

    expect(signalsInFlight(state, Date.parse('2026-10-27T10:00:09Z'))).toHaveLength(1);
    expect(signalsInFlight(state, Date.parse('2026-10-27T10:00:10Z'))).toHaveLength(0);
  });
});

describe('dustStormActive', () => {
  const event = (code: string) => ({ code, severity: 'INFO' as const, message: '', roverTime: '2026-10-27T12:00:00Z' });
  const withLog = (...codes: string[]) =>
    codes.reduce((state, code) => missionReducer(state, { type: 'rover-event', event: event(code) }), initialMission);

  it('follows the latest storm event, ignoring others', () => {
    expect(dustStormActive(initialMission)).toBe(false);
    expect(dustStormActive(withLog('DUST_STORM_STARTED', 'SEQUENCE_GAP_SKIPPED'))).toBe(true);
    expect(dustStormActive(withLog('DUST_STORM_STARTED', 'DUST_STORM_ENDED'))).toBe(false);
  });
});
