-- Everything streamed to browsers over SSE, written in the same transaction as the change it
-- describes. The id is the SSE event id: a reconnecting client sends the last id it saw
-- (Last-Event-ID) and gets every later event of that rover replayed in order.
CREATE TABLE rover_events
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rover_id   BIGINT      NOT NULL REFERENCES rovers (id),
    type       VARCHAR(32) NOT NULL,
    data       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX rover_events_rover_idx ON rover_events (rover_id, id);
CREATE INDEX rover_events_created_idx ON rover_events (created_at);
