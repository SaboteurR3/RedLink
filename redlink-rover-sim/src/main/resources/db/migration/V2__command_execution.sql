-- Execution bookkeeping for the flight software (ticket 06).

-- Last sequence number the rover started (or refused/skipped). The next command to run is the
-- lowest received sequence number above it.
ALTER TABLE rover_state ADD COLUMN last_sequence_no BIGINT NOT NULL DEFAULT 0;

ALTER TABLE received_commands
    ADD COLUMN total_steps  INT,
    ADD COLUMN steps_done   INT NOT NULL DEFAULT 0,
    ADD COLUMN next_step_at TIMESTAMPTZ,
    ADD CONSTRAINT received_commands_steps_ck CHECK (steps_done >= 0 AND (total_steps IS NULL OR steps_done <= total_steps));
