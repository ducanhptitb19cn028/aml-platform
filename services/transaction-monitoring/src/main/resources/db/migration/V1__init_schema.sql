-- V1: Initial schema for the Transaction Monitoring bounded context.

CREATE TABLE IF NOT EXISTS transactions (
    id                      UUID         PRIMARY KEY,
    customer_id             VARCHAR(64)  NOT NULL,
    amount                  NUMERIC(19,4) NOT NULL CHECK (amount >= 0),
    currency                CHAR(3)      NOT NULL,
    origin_country          CHAR(2)      NOT NULL,
    destination_country     CHAR(2)      NOT NULL,
    channel                 VARCHAR(32)  NOT NULL,
    occurred_at             TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transactions_customer_time
    ON transactions (customer_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS alerts (
    id                      UUID         PRIMARY KEY,
    transaction_id          UUID         NOT NULL,
    customer_id             VARCHAR(64)  NOT NULL,
    risk_score              INTEGER      NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    fired_rule_ids          VARCHAR(512) NOT NULL,
    rationale               VARCHAR(4000) NOT NULL,
    raised_at               TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_alerts_customer ON alerts (customer_id);
CREATE INDEX IF NOT EXISTS idx_alerts_transaction ON alerts (transaction_id);
CREATE INDEX IF NOT EXISTS idx_alerts_raised_at ON alerts (raised_at DESC);
