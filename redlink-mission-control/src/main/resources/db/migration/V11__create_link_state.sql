-- State of the deep-space link that operators control (ticket 19). Exactly one row.
CREATE TABLE link_state
(
    id                 SMALLINT PRIMARY KEY DEFAULT 1,
    -- Solar conjunction: the Sun is between Earth and Mars; nothing gets through either way.
    conjunction_active BOOLEAN     NOT NULL DEFAULT FALSE,
    changed_by         VARCHAR(64),
    changed_at         TIMESTAMPTZ,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT link_state_single_row_ck CHECK (id = 1)
);

INSERT INTO link_state (id) VALUES (1);
