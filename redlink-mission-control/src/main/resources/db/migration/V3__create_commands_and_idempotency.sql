-- Commands operators send to rovers, with their full lifecycle as Earth knows it.
CREATE TABLE commands
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rover_id         BIGINT       NOT NULL REFERENCES rovers (id),
    -- Per-rover transmission order, assigned when the command is put on the uplink (ticket 10).
    sequence_no      BIGINT,
    type             VARCHAR(32)  NOT NULL,
    command          JSONB        NOT NULL,
    status           VARCHAR(24)  NOT NULL,
    progress_percent INT          NOT NULL DEFAULT 0,
    created_by       VARCHAR(64)  NOT NULL,
    cancelled_by     VARCHAR(64),
    failure_code     VARCHAR(64),
    failure_detail   VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    transmitted_at   TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT commands_rover_sequence_uk UNIQUE (rover_id, sequence_no),
    CONSTRAINT commands_status_ck CHECK (status IN ('PENDING_APPROVAL', 'QUEUED', 'TRANSMITTED', 'RECEIVED',
                                                    'EXECUTING', 'COMPLETED', 'FAILED', 'REFUSED_BY_ROVER',
                                                    'CANCELLED', 'REJECTED')),
    CONSTRAINT commands_progress_ck CHECK (progress_percent BETWEEN 0 AND 100)
);

-- "Commands of rover X, newest first", optionally by status.
CREATE INDEX commands_rover_idx ON commands (rover_id, id DESC);
CREATE INDEX commands_rover_status_idx ON commands (rover_id, status, id DESC);

-- Idempotency-Key bookkeeping (draft-ietf-httpapi-idempotency-key-header). One row per user and
-- key; the response is stored as TEXT (not jsonb, which would reorder keys) so a replay is
-- byte-for-byte the original.
CREATE TABLE idempotency_records
(
    username          VARCHAR(64)  NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    request_hash      CHAR(64)     NOT NULL,
    response_status   INT,
    response_location VARCHAR(255),
    response_body     TEXT,
    created_at        TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (username, idempotency_key)
);

CREATE INDEX idempotency_records_created_idx ON idempotency_records (created_at);
