-- V2: Idempotency tracking for inbound Kafka events.
--
-- WHY: Kafka's delivery semantics are at-least-once by default. Without
-- this table, a redelivered AlertRaised would open a duplicate Case for
-- the same alertId — bad for auditors and bad for investigators.
--
-- HOW: The Kafka listener writes to this table inside the same DB
-- transaction as the Case insert. PRIMARY KEY (event_id) + INSERT ...
-- ON CONFLICT DO NOTHING means a duplicate event silently no-ops.
--
-- The processor column lets us distinguish dedup state per consumer if
-- we ever add a second listener that reads the same topic.

CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID         NOT NULL,
    processor       VARCHAR(64)  NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, processor)
);

CREATE INDEX IF NOT EXISTS idx_processed_events_at
    ON processed_events (processed_at);

-- Retention: a periodic job deletes rows older than 30 days. That's
-- well past Kafka's default retention so we will never reprocess
-- something we've already forgotten.
