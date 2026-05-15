-- V3: Convert CHAR columns to VARCHAR to match JPA entity mappings.
-- Hibernate validates bpchar vs varchar as a type mismatch; ALTER is safe
-- because CHAR and VARCHAR are storage-compatible in PostgreSQL.

ALTER TABLE transactions ALTER COLUMN currency           TYPE VARCHAR(3);
ALTER TABLE transactions ALTER COLUMN origin_country     TYPE VARCHAR(2);
ALTER TABLE transactions ALTER COLUMN destination_country TYPE VARCHAR(2);
