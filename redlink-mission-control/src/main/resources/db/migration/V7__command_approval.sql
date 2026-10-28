-- Approval workflow (ticket 14).
ALTER TABLE commands
    -- When the command became QUEUED: at creation, or at approval. The uplink hold (the last
    -- chance to cancel) counts from here, so approving an old command never skips it.
    ADD COLUMN queued_at       TIMESTAMPTZ,
    -- Who approved or rejected a command that needs approval, when, and why.
    ADD COLUMN decided_by      VARCHAR(64),
    ADD COLUMN decided_at      TIMESTAMPTZ,
    ADD COLUMN decision_reason VARCHAR(500);

UPDATE commands SET queued_at = created_at WHERE status <> 'PENDING_APPROVAL';

ALTER TABLE commands
    ADD CONSTRAINT commands_queued_at_ck
        CHECK (queued_at IS NOT NULL OR status IN ('PENDING_APPROVAL', 'CANCELLED', 'REJECTED'));
