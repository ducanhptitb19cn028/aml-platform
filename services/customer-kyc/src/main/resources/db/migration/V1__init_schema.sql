-- V1: Initial schema for the Customer / KYC bounded context.
--
-- customer_id is the master data primary key — opaque string from
-- onboarding, NOT a UUID generated here. Length capped at 64.

CREATE TABLE IF NOT EXISTS customers (
    id                      VARCHAR(64)   PRIMARY KEY,
    legal_name              VARCHAR(256)  NOT NULL,
    status                  VARCHAR(32)   NOT NULL,
    risk_tier               VARCHAR(32)   NOT NULL,
    politically_exposed     BOOLEAN       NOT NULL DEFAULT FALSE,
    sanctioned              BOOLEAN       NOT NULL DEFAULT FALSE,
    residency_country       CHAR(2)       NOT NULL,
    onboarded_at            TIMESTAMPTZ   NOT NULL,
    last_updated_at         TIMESTAMPTZ   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,

    -- The aggregate's own invariant — sanctioned implies PROHIBITED.
    -- We enforce in domain code AND at the DB level so a misbehaving
    -- direct insert can't leave the table in an inconsistent state.
    CONSTRAINT sanctioned_must_be_prohibited
        CHECK (NOT sanctioned OR risk_tier = 'PROHIBITED')
);

-- Lookup is in the hot path; keep the index unsurprising.
CREATE INDEX IF NOT EXISTS idx_customers_residency ON customers (residency_country);
CREATE INDEX IF NOT EXISTS idx_customers_tier ON customers (risk_tier);
