-- V3: Convert CHAR columns to VARCHAR to match JPA entity mappings.
-- Hibernate validates bpchar vs varchar as a type mismatch; ALTER is safe
-- because CHAR and VARCHAR are storage-compatible in PostgreSQL.

ALTER TABLE customers ALTER COLUMN residency_country TYPE VARCHAR(2);
