-- V2: Transactional outbox for AlertRaised events.
--
-- WHY: Without this, sending to Kafka happens inside the DB transaction.
-- If Kafka returns an ACK but the DB commit fails (e.g. connection
-- lost), we have an event in Kafka with no corresponding alert in the
-- DB. Or vice versa. In a regulated domain, both directions are
-- unacceptable.
--
-- HOW: The application service writes the aggregate AND the outbox row
-- in one atomic DB transaction. A separate scheduled worker reads
-- pending outbox rows, sends each to Kafka, and marks them dispatched.
-- Failure modes:
--   - DB commit fails  → no Kafka send (correct)
--   - Worker crashes before Kafka send → row stays pending → retried (correct)
--   - Kafka send fails → row stays pending → retried (correct)
--   - Worker crashes AFTER Kafka send but BEFORE marking dispatched
--     → at-least-once delivery → consumer dedups via processed_events
--
-- This is the standard "Polling Publisher" variant of the outbox
-- pattern. A more advanced variant uses Postgres logical replication
-- (e.g. Debezium) to remove the polling worker entirely. For research
-- workloads, polling is simpler and the latency overhead (a few hundred
-- ms) is irrelevant.

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

-- The worker query: pending events, oldest first
CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox (created_at)
    WHERE dispatched_at IS NULL;

-- Periodic cleanup: keep dispatched rows for 7 days for debugging
CREATE INDEX IF NOT EXISTS idx_outbox_dispatched
    ON outbox (dispatched_at)
    WHERE dispatched_at IS NOT NULL;
