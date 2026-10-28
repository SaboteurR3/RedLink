-- Every telemetry packet received from Mars. packet_id is the dedupe key: the downlink delivers at
-- least once, the unique constraint makes each packet take effect exactly once.
CREATE TABLE telemetry
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    packet_id   UUID        NOT NULL,
    rover_id    BIGINT      NOT NULL REFERENCES rovers (id),
    type        VARCHAR(32) NOT NULL,
    -- For COMMAND_STATUS. No foreign key: a report about a command Earth does not know is kept for audit.
    command_id  BIGINT,
    payload     JSONB       NOT NULL,
    -- When it happened on Mars; received_at is when Earth heard about it (one light time later).
    rover_time  TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT telemetry_packet_uk UNIQUE (packet_id),
    CONSTRAINT telemetry_type_ck CHECK (type IN ('COMMAND_STATUS', 'ROVER_STATE', 'EVENT'))
);

-- History: newest first per rover.
CREATE INDEX telemetry_rover_time_idx ON telemetry (rover_id, rover_time DESC, id DESC);
CREATE INDEX telemetry_command_idx ON telemetry (command_id) WHERE command_id IS NOT NULL;
