// Domain types for the UI, derived from the generated OpenAPI types (schema.d.ts) so a backend
// change that breaks the contract breaks the frontend build.
//
// springdoc marks no record component as required, so every generated property is optional.
// The API always sends these fields (nullable ones as null), so they are narrowed here once.
import type { components } from './schema';

type S = components['schemas'];

/** All properties present (the API sends every field of a record). */
type Present<T> = { [K in keyof T]-?: Exclude<T[K], undefined> };

export type Heading = 'NORTH' | 'EAST' | 'SOUTH' | 'WEST';
export type RoverMode = 'NOMINAL' | 'SAFE_MODE';
export type Camera = 'NAVCAM' | 'MASTCAM';
export type CommandType = Present<S['CommandResponse']>['type'];
export type CommandStatus = Present<S['CommandResponse']>['status'];

export type LastKnownState = Omit<Present<S['LastKnownState']>, 'reportedAt'> & {
  /** Mars time of the telemetry; null = nothing heard since landing. */
  reportedAt: string | null;
};

export type Rover = Omit<Present<S['RoverResponse']>, 'lastKnown'> & { lastKnown: LastKnownState };

/** A command as sent in requests: the wire format of the command dictionary. */
export type RoverCommand =
  | { type: 'MOVE'; distanceMeters: number }
  | { type: 'ROTATE'; heading: Heading }
  | { type: 'TAKE_PHOTO'; camera: Camera }
  | { type: 'DRILL_SAMPLE' }
  | { type: 'CHARGE'; minutes: number }
  | { type: 'REBOOT' };

export type Link = { href: string; method?: string };

export type Approval = Omit<Present<S['Approval']>, 'decidedBy' | 'decidedAt' | 'reason'> & {
  decidedBy: string | null;
  decidedAt: string | null;
  reason: string | null;
};

export type Command = Omit<
  Present<S['CommandResponse']>,
  'command' | 'sequenceNo' | 'transmittedAt' | 'finishedAt' | 'failure' | 'approval' | 'mission' | '_links'
> & {
  command: RoverCommand;
  sequenceNo: number | null;
  transmittedAt: string | null;
  finishedAt: string | null;
  failure: { code: string; detail: string } | null;
  /** Only for commands that need a flight director's approval (DRILL_SAMPLE). */
  approval: Approval | null;
  /** Set when the command is step `step` (one-based) of a mission. */
  mission: { id: number; step: number } | null;
  /** What the command's state allows next; the server decides (HATEOAS). */
  _links: { self: Link; rover: Link; mission?: Link; cancel?: Link; approve?: Link; reject?: Link };
};

export type MissionRequest = { name: string; steps: RoverCommand[] };
export type MissionStatus = Present<S['MissionResponse']>['status'];
export type Mission = Omit<Present<S['MissionResponse']>, 'steps' | 'forecast' | 'progress' | '_links'> & {
  steps: Command[];
  /** Where Earth predicted the rover would be after the last step. */
  forecast: Present<S['Forecast']>;
  progress: Present<S['Progress']>;
  _links: { self: Link; rover: Link; cancel?: Link };
};

/** A photo's metadata; the PNG is `_links.image` requested with its `type` as Accept. */
export type PhotoLink = { href: string; type?: string };
export type Photo = Omit<Present<S['PhotoResponse']>, '_links'> & {
  _links: { self: PhotoLink; image: PhotoLink; rover: PhotoLink; command: PhotoLink };
};

export type Conjunction = Omit<Present<S['ConjunctionResponse']>, 'changedBy' | 'changedAt'> & {
  changedBy: string | null;
  changedAt: string | null;
};
export type LinkStatus = Omit<Present<S['LinkStatus']>, 'window' | 'conjunction'> & {
  window: Omit<Present<S['WindowStatus']>, 'nextChangeAt'> & { nextChangeAt: string | null };
  conjunction: Conjunction;
};
/** A representation with its entity tag (for If-Match). */
export type Tagged<T> = { body: T; etag: string | null };

export type Site = Present<S['SiteResponse']> & { start: Present<S['Cell']> };
export type Me = Present<S['MeResponse']>;
export type Ticket = Present<S['TicketResponse']>;

export type Page<T> = { content: T[]; page: { size: number; number: number; totalElements: number; totalPages: number } };

/** `uplink` stream event: a command left Earth. */
export type UplinkTransmission = { commandId: number; sequenceNo: number; transmittedAt: string; arrivesAt: string };

/** `rover-event` stream event. */
export type RoverEventMessage = {
  code: string;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  message: string;
  roverTime: string;
};

/**
 * A drill sample (`sample` event, or a SAMPLE telemetry report). x, y and heading are where the
 * rover stood; a ROCK sample comes from the next cell in its heading.
 */
export type Sample = {
  commandId: number;
  material: 'SOIL' | 'ROCK';
  x: number;
  y: number;
  heading: Heading;
  roverTime: string;
};

/** RFC 9457 Problem Details, as every API error is sent. */
export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: string[];
  /** `rover-busy`: the unfinished commands that block a mission. */
  blockingCommands?: { id: number; type: CommandType; status: CommandStatus; cancellable: boolean }[];
};

/** The rover's published spec sheet (`GET /specs`): costs, limits, step durations. */
export type Specs = Present<S['SpecsResponse']>;

export const COMMAND_TYPES: readonly CommandType[] = ['MOVE', 'ROTATE', 'TAKE_PHOTO', 'DRILL_SAMPLE', 'CHARGE', 'REBOOT'];

export const FINAL_STATUSES: ReadonlySet<CommandStatus> = new Set<CommandStatus>([
  'COMPLETED',
  'FAILED',
  'REFUSED_BY_ROVER',
  'CANCELLED',
  'REJECTED',
]);

/** One position along a previewed plan; `step` −1 is the start. */
export type PathPoint = Present<S['PathPoint']>;

/** A dry run of a command or plan (`POST /plan-previews`). */
export type PlanPreview = Omit<Present<S['PlanPreviewResponse']>, 'path' | 'end' | 'problem'> & {
  path: PathPoint[];
  end: Present<S['Forecast']>;
  /** The first step that would fail (one-based), and the cell a failing drive would enter. */
  problem: { step: number; reason: string; x: number | null; y: number | null } | null;
};

/** The cheapest drive to a cell (`POST /routes`): the commands and their preview. */
export type RouteProposal = { steps: RoverCommand[]; preview: PlanPreview };

export const WEBHOOK_EVENTS = ['command.completed', 'command.failed', 'rover.safe_mode', 'rover.battery_low'] as const;
export type Webhook = Omit<Present<S['WebhookResponse']>, '_links'>;
/** Only in the answer to the registration: the signing secret, shown this once. */
export type CreatedWebhook = Omit<Present<S['CreatedWebhookResponse']>, '_links'>;
export type Delivery = Omit<Present<S['DeliveryResponse']>, 'deliveredAt' | 'nextAttemptAt' | 'lastStatusCode' | 'lastError'> & {
  deliveredAt: string | null;
  nextAttemptAt: string | null;
  lastStatusCode: number | null;
  lastError: string | null;
};

export type AuditEntry = Omit<Present<S['AuditEntryResponse']>, 'detail'> & { detail: string | null };
export type AuditAction = AuditEntry['action'];
