-- Per-rover uplink sequence numbers. Numbers are handed out at transmission, so a command that is
-- cancelled while queued never leaves a gap (the rover waits for gaps, see rover-sim).
CREATE TABLE uplink_sequences
(
    rover_id         BIGINT PRIMARY KEY REFERENCES rovers (id),
    last_sequence_no BIGINT NOT NULL DEFAULT 0
);

INSERT INTO uplink_sequences (rover_id)
SELECT id FROM rovers;

-- Uplink outbox: one packet per transmitted command, delivered to the rover once its light-time
-- delay has passed. The packet is TEXT: exactly the bytes that are signed and sent.
CREATE TABLE uplink_packets
(
    packet_id       UUID PRIMARY KEY,
    command_id      BIGINT       NOT NULL UNIQUE REFERENCES commands (id),
    rover_id        BIGINT       NOT NULL REFERENCES rovers (id),
    packet          TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL,
    deliver_at      TIMESTAMPTZ  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    last_error      VARCHAR(500),
    CONSTRAINT uplink_packets_status_ck CHECK (status IN ('PENDING', 'SENT', 'REJECTED'))
);

CREATE INDEX uplink_packets_due_idx ON uplink_packets (next_attempt_at) WHERE status = 'PENDING';

-- The transmitter looks for queued commands, oldest first.
CREATE INDEX commands_queued_idx ON commands (id) WHERE status = 'QUEUED';
