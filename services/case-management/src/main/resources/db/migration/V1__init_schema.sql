-- V1: Initial schema for the Case Management bounded context.
--
-- Design notes:
--   * Primary key is UUID v4 (matches CaseId.generate())
--   * status stored as TEXT to make migrations forgiving when adding states
--   * version column for optimistic locking — required because the
--     application service is transactional and may be retried
--   * Indexes match the access patterns: lookup by id (PK), by customer
--     for KYC join, and by status for investigator dashboards

CREATE TABLE IF NOT EXISTS cases (
    id                      UUID         PRIMARY KEY,
    alert_id                VARCHAR(64)  NOT NULL,
    customer_id             VARCHAR(64)  NOT NULL,
    risk_score              INTEGER      NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    status                  VARCHAR(32)  NOT NULL,
    assigned_investigator   VARCHAR(64),
    opened_at               TIMESTAMPTZ  NOT NULL,
    last_updated_at         TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_cases_customer_id ON cases (customer_id);
CREATE INDEX IF NOT EXISTS idx_cases_status      ON cases (status);
CREATE INDEX IF NOT EXISTS idx_cases_opened_at   ON cases (opened_at DESC);
