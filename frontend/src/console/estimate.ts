import type { Heading, LastKnownState, RoverCommand, Specs } from '../api/types';

/** What a command will roughly cost and take, by Earth's last knowledge and the rover's spec sheet. */
export type Estimate = {
  /** Seconds the rover works on it on Mars (null when it depends on a rover setting, e.g. charging). */
  marsSeconds: number | null;
  /** Battery change in percent: negative costs, positive gains; a range where the terrain decides (sand). */
  batteryMin: number;
  batteryMax: number;
  /** Storage used, in percent. */
  storage: number;
  /** Rough time until the result shows on screen: hold on Earth + light time both ways + work on Mars. */
  seeResultSeconds: number | null;
  /** Things the operator should know before sending; the server still decides. */
  warnings: string[];
};

export type LinkTiming = { oneWayDelaySeconds: number; queueHoldSeconds: number };

const ORDER: Heading[] = ['NORTH', 'EAST', 'SOUTH', 'WEST'];

/** Quarter turns the short way, as the rover turns (a half turn is two). */
export function quarterTurns(from: Heading, to: Heading): number {
  const clockwise = (ORDER.indexOf(to) - ORDER.indexOf(from) + 4) % 4;
  return clockwise === 3 ? 1 : clockwise;
}

export function estimate(command: RoverCommand, rover: LastKnownState, specs: Specs, link: LinkTiming | null): Estimate {
  const warnings: string[] = [];
  let marsSeconds: number | null = 0;
  let batteryMin = 0;
  let batteryMax = 0;
  let storage = 0;

  if (rover.mode === 'SAFE_MODE' && command.type !== 'REBOOT') {
    warnings.push('The rover was last reported in safe mode: only REBOOT is accepted.');
  }

  switch (command.type) {
    case 'MOVE': {
      marsSeconds = command.distanceMeters * specs.moveSecondsPerMeter;
      batteryMin = -command.distanceMeters * specs.moveCostPerMeterSand;
      batteryMax = -command.distanceMeters * specs.moveCostPerMeterGround;
      if (rover.batteryPercent < command.distanceMeters * specs.moveCostPerMeterGround) {
        warnings.push(`Not enough battery for ${command.distanceMeters} m by the last report (${rover.batteryPercent} %).`);
      } else if (rover.batteryPercent < -batteryMin) {
        warnings.push('The battery may run out on the way if the route crosses sand.');
      }
      break;
    }
    case 'ROTATE': {
      const turns = quarterTurns(rover.heading, command.heading);
      marsSeconds = turns * specs.rotateSecondsPerQuarterTurn;
      batteryMin = batteryMax = -turns * specs.rotateCost;
      if (turns === 0) warnings.push(`The rover already faces ${command.heading}: nothing would happen.`);
      break;
    }
    case 'TAKE_PHOTO':
      marsSeconds = specs.photoSeconds;
      batteryMin = batteryMax = -specs.photoCost;
      storage = specs.photoStorage;
      break;
    case 'DRILL_SAMPLE':
      marsSeconds = specs.drillSeconds;
      batteryMin = batteryMax = -specs.drillCost;
      storage = specs.drillStorage;
      if (rover.batteryPercent < specs.drillMinBattery) {
        warnings.push(`Drilling needs ${specs.drillMinBattery} % battery; the last report says ${rover.batteryPercent} %.`);
      }
      warnings.push('Drilling waits for another flight director to approve it.');
      break;
    case 'CHARGE':
      marsSeconds = null; // one simulated minute is a rover setting Earth does not know
      batteryMin = batteryMax = Math.min(100 - rover.batteryPercent, command.minutes * specs.chargePerMinute);
      break;
    case 'REBOOT':
      marsSeconds = specs.rebootSeconds;
      break;
  }

  if (storage > 0 && rover.storageUsedPercent + storage > 100) {
    warnings.push(`Storage is ${rover.storageUsedPercent} % full: this needs ${storage} % more.`);
  }

  const seeResultSeconds =
    link && marsSeconds !== null ? link.queueHoldSeconds + 2 * link.oneWayDelaySeconds + marsSeconds : null;
  return { marsSeconds, batteryMin: round(batteryMin), batteryMax: round(batteryMax), storage, seeResultSeconds, warnings };
}

/** "−3 %" or "−3 to −6 %" or "+10 %". */
export function formatBattery(e: Pick<Estimate, 'batteryMin' | 'batteryMax'>): string {
  const sign = (n: number) => (n > 0 ? `+${n}` : n < 0 ? `−${-n}` : '0');
  return e.batteryMin === e.batteryMax ? `${sign(e.batteryMin)} %` : `${sign(e.batteryMax)} to ${sign(e.batteryMin)} %`;
}

function round(n: number): number {
  return Math.round(n * 100) / 100 + 0; // + 0 turns −0 into 0
}
