-- The rover's true state. Only the flight software (rover-sim) ever sees it; Earth learns about it
-- through delayed telemetry.
CREATE TABLE rover_state
(
    rover_id             BIGINT PRIMARY KEY,
    name                 VARCHAR(64)      NOT NULL,
    site_id              VARCHAR(64)      NOT NULL,
    x                    INT              NOT NULL,
    y                    INT              NOT NULL,
    heading              VARCHAR(8)       NOT NULL,
    battery_percent      DOUBLE PRECISION NOT NULL,
    temperature_celsius  DOUBLE PRECISION NOT NULL,
    storage_used_percent INT              NOT NULL,
    mode                 VARCHAR(16)      NOT NULL,
    version              BIGINT           NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT rover_state_heading_ck CHECK (heading IN ('NORTH', 'EAST', 'SOUTH', 'WEST')),
    CONSTRAINT rover_state_battery_ck CHECK (battery_percent BETWEEN 0 AND 100),
    CONSTRAINT rover_state_storage_ck CHECK (storage_used_percent BETWEEN 0 AND 100),
    CONSTRAINT rover_state_mode_ck CHECK (mode IN ('NOMINAL', 'SAFE_MODE'))
);

-- Rover 1 lands at the start cell of the default site (sites/jezero-sim/site.properties).
INSERT INTO rover_state (rover_id, name, site_id, x, y, heading, battery_percent, temperature_celsius,
                         storage_used_percent, mode)
VALUES (1, 'Perseverance-sim', 'jezero-sim', 32, 32, 'NORTH', 100, -20, 0, 'NOMINAL');

-- Every command packet the rover accepted. packet_id is the dedupe key: the link delivers at least
-- once, the primary key makes every packet take effect exactly once.
CREATE TABLE received_commands
(
    packet_id        UUID PRIMARY KEY,
    rover_id         BIGINT      NOT NULL REFERENCES rover_state (rover_id),
    command_id       BIGINT      NOT NULL,
    sequence_no      BIGINT      NOT NULL,
    type             VARCHAR(32) NOT NULL,
    command          JSONB       NOT NULL,
    status           VARCHAR(16) NOT NULL,
    progress_percent INT         NOT NULL DEFAULT 0,
    sent_at          TIMESTAMPTZ NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL,
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    failure_code     VARCHAR(64),
    failure_detail   VARCHAR(500),
    -- The rover executes in sequence order; two different packets can never claim the same slot.
    CONSTRAINT received_commands_rover_sequence_uk UNIQUE (rover_id, sequence_no),
    CONSTRAINT received_commands_status_ck CHECK (status IN ('RECEIVED', 'EXECUTING', 'COMPLETED', 'FAILED', 'REFUSED')),
    CONSTRAINT received_commands_progress_ck CHECK (progress_percent BETWEEN 0 AND 100)
);

-- The executor (ticket 06) looks for the next unfinished command of a rover.
CREATE INDEX received_commands_open_idx ON received_commands (rover_id, sequence_no)
    WHERE status IN ('RECEIVED', 'EXECUTING');

-- Telemetry outbox: written in the same transaction as the change it reports, sent to Earth once
-- deliver_at has passed (store-and-forward, ticket 07).
CREATE TABLE downlink_packets
(
    packet_id       UUID PRIMARY KEY,
    rover_id        BIGINT       NOT NULL REFERENCES rover_state (rover_id),
    type            VARCHAR(32)  NOT NULL,
    packet          JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL,
    deliver_at      TIMESTAMPTZ  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    last_error      VARCHAR(500),
    CONSTRAINT downlink_packets_status_ck CHECK (status IN ('PENDING', 'SENT'))
);

CREATE INDEX downlink_packets_due_idx ON downlink_packets (next_attempt_at) WHERE status = 'PENDING';
