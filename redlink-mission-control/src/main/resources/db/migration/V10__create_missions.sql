-- Missions: an ordered plan of commands, checked as a whole before any step is queued (ticket 17).
-- The mission's status is not stored: it is derived from its steps, the single source of truth.
CREATE TABLE missions
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rover_id   BIGINT       NOT NULL REFERENCES rovers (id),
    name       VARCHAR(100) NOT NULL,
    created_by VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    -- Earth's prediction of the rover's state after the last step, made when the plan was checked.
    forecast   JSONB        NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX missions_rover_idx ON missions (rover_id, id DESC);

ALTER TABLE commands
    ADD COLUMN mission_id   BIGINT REFERENCES missions (id),
    ADD COLUMN mission_step INT,
    ADD CONSTRAINT commands_mission_step_uk UNIQUE (mission_id, mission_step),
    ADD CONSTRAINT commands_mission_step_ck CHECK ((mission_id IS NULL) = (mission_step IS NULL));
