-- Photos taken by TAKE_PHOTO and downlinked as PHOTO telemetry (ticket 16).
ALTER TABLE telemetry DROP CONSTRAINT telemetry_type_ck;
ALTER TABLE telemetry ADD CONSTRAINT telemetry_type_ck
    CHECK (type IN ('COMMAND_STATUS', 'ROVER_STATE', 'EVENT', 'PHOTO'));

CREATE TABLE photos
(
    -- The PHOTO telemetry packet's id: random (not guessable) and already deduplicated.
    id          UUID PRIMARY KEY,
    rover_id    BIGINT      NOT NULL REFERENCES rovers (id),
    -- Like telemetry.command_id: what the rover reported, so no foreign key.
    command_id  BIGINT      NOT NULL,
    camera      VARCHAR(16) NOT NULL,
    x           INT         NOT NULL,
    y           INT         NOT NULL,
    heading     VARCHAR(8)  NOT NULL,
    width       INT         NOT NULL,
    height      INT         NOT NULL,
    size_bytes  INT         NOT NULL,
    -- Hex SHA-256 of the PNG; the strong ETag of the image.
    sha256      VARCHAR(64) NOT NULL,
    image       BYTEA       NOT NULL,
    -- Mars time the photo was taken.
    taken_at    TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT photos_camera_ck CHECK (camera IN ('NAVCAM', 'MASTCAM')),
    CONSTRAINT photos_heading_ck CHECK (heading IN ('NORTH', 'EAST', 'SOUTH', 'WEST'))
);

CREATE INDEX photos_rover_taken_idx ON photos (rover_id, taken_at DESC, id DESC);
