-- Webhooks: HTTP callbacks to other systems when something happens (ticket 15).
CREATE TABLE webhooks
(
    -- Random, so ids in URLs cannot be guessed or enumerated.
    id         UUID PRIMARY KEY,
    owner      VARCHAR(64)   NOT NULL,
    url        VARCHAR(2048) NOT NULL,
    -- HMAC key for signing deliveries; returned to the owner only once, at creation.
    secret     VARCHAR(128)  NOT NULL,
    events     TEXT[]        NOT NULL,
    active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT webhooks_events_ck CHECK (cardinality(events) > 0)
);

CREATE INDEX webhooks_owner_idx ON webhooks (owner, created_at);

-- Delivery outbox and log: one row per (event, webhook), written in the transaction of the change.
CREATE TABLE webhook_deliveries
(
    -- Sent as X-RedLink-Delivery: receivers dedupe on it (delivery is at least once).
    id               UUID PRIMARY KEY,
    webhook_id       UUID          NOT NULL REFERENCES webhooks (id) ON DELETE CASCADE,
    event            VARCHAR(32)   NOT NULL,
    -- The exact body that is signed and sent.
    payload          TEXT          NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts         INT           NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    delivered_at     TIMESTAMPTZ,
    last_status_code INT,
    last_error       VARCHAR(500),
    CONSTRAINT webhook_deliveries_status_ck CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_deliveries_webhook_idx ON webhook_deliveries (webhook_id, created_at DESC);
