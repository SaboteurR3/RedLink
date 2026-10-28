-- Who changed what and when (ticket 31). Append-only: rows are never updated or deleted by the app.
CREATE TABLE audit_log
(
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    -- What was acted on, e.g. 'command:42', 'mission:3', 'webhook:<uuid>', 'link:conjunction'.
    target      VARCHAR(100) NOT NULL,
    detail      VARCHAR(500)
);

CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC, id DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor, occurred_at DESC);
