-- V2: Outbox table — same pattern as the other services.
-- See transaction-monitoring/V2__outbox.sql for the rationale.

CREATE TABLE IF NOT EXISTS outbox (
    id              UUID         PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    dispatched_at   TIMESTAMPTZ,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(2000)
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (created_at)
    WHERE dispatched_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_dispatched
    ON outbox (dispatched_at)
    WHERE dispatched_at IS NOT NULL;
