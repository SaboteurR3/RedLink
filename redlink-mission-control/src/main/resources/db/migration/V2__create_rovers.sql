-- Earth's view of each rover: the LAST KNOWN state, as reported by telemetry. Mission control
-- never sees the rover's true state (that lives in rover-sim's own database).
CREATE TABLE rovers
(
    id                        BIGINT PRIMARY KEY,
    name                      VARCHAR(64)      NOT NULL,
    site_id                   VARCHAR(64)      NOT NULL,
    last_x                    INT              NOT NULL,
    last_y                    INT              NOT NULL,
    last_heading              VARCHAR(8)       NOT NULL,
    last_battery_percent      DOUBLE PRECISION NOT NULL,
    last_temperature_celsius  DOUBLE PRECISION NOT NULL,
    last_storage_used_percent INT              NOT NULL,
    last_mode                 VARCHAR(16)      NOT NULL,
    -- When the last telemetry was recorded on Mars; NULL = nothing heard since landing.
    last_telemetry_at         TIMESTAMPTZ,
    -- Optimistic locking; also the source of the ETag.
    version                   BIGINT           NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT rovers_heading_ck CHECK (last_heading IN ('NORTH', 'EAST', 'SOUTH', 'WEST')),
    CONSTRAINT rovers_battery_ck CHECK (last_battery_percent BETWEEN 0 AND 100),
    CONSTRAINT rovers_storage_ck CHECK (last_storage_used_percent BETWEEN 0 AND 100),
    CONSTRAINT rovers_mode_ck CHECK (last_mode IN ('NOMINAL', 'SAFE_MODE'))
);

-- Rover 1, as planned at landing (site jezero-sim, start cell 32,32).
INSERT INTO rovers (id, name, site_id, last_x, last_y, last_heading, last_battery_percent,
                    last_temperature_celsius, last_storage_used_percent, last_mode)
VALUES (1, 'Perseverance-sim', 'jezero-sim', 32, 32, 'NORTH', 100, -20, 0, 'NOMINAL');
