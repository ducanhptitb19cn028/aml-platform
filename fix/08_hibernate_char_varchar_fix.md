# Fix 08 — Hibernate Schema Validation: CHAR vs VARCHAR Mismatch

## Problem
`customer-kyc` and `transaction-monitoring` crashed with:
```
Schema-validation: wrong column type encountered in column [residency_country]
in table [customers]; found [bpchar (Types#CHAR)], but expecting [varchar(2) (Types#VARCHAR)]

Schema-validation: wrong column type encountered in column [currency]
in table [transactions]; found [bpchar (Types#CHAR)], but expecting [varchar(3) (Types#VARCHAR)]
```

## Root Cause
The Flyway V1 migrations created fixed-length character columns using SQL `CHAR(n)`.
PostgreSQL stores `CHAR(n)` internally as `bpchar` (blank-padded character).
The JPA entities use `@Column(length = n)` which Hibernate maps to `varchar(n)`.
Hibernate's schema validation treats `bpchar` and `varchar` as different types → crash.

Cannot simply edit V1 migration: Flyway validates checksums of applied migrations and
would throw `FlywayException: Validate failed: Migration checksum mismatch`.

## Fix

### Added V3 migration for `customer-kyc`
**File:** `services/customer-kyc/src/main/resources/db/migration/V3__fix_char_to_varchar.sql`
```sql
ALTER TABLE customers ALTER COLUMN residency_country TYPE VARCHAR(2);
```

### Added V3 migration for `transaction-monitoring`
**File:** `services/transaction-monitoring/src/main/resources/db/migration/V3__fix_char_to_varchar.sql`
```sql
ALTER TABLE transactions ALTER COLUMN currency            TYPE VARCHAR(3);
ALTER TABLE transactions ALTER COLUMN origin_country      TYPE VARCHAR(2);
ALTER TABLE transactions ALTER COLUMN destination_country TYPE VARCHAR(2);
```

PostgreSQL `ALTER COLUMN TYPE` from `CHAR(n)` to `VARCHAR(n)` is safe — no data conversion
needed, no table rewrite required. Existing data is preserved exactly.

### For live clusters (without image rebuild)
Apply the ALTER directly to the running Postgres pods:
```powershell
kubectl exec -n data postgres-kyc-postgresql-0 -- psql -U kyc -d customer_kyc -c `
  "ALTER TABLE customers ALTER COLUMN residency_country TYPE VARCHAR(2);"

kubectl exec -n data postgres-monitoring-postgresql-0 -- psql -U tx_mon -d transaction_monitoring -c `
  "ALTER TABLE transactions ALTER COLUMN currency TYPE VARCHAR(3);
   ALTER TABLE transactions ALTER COLUMN origin_country TYPE VARCHAR(2);
   ALTER TABLE transactions ALTER COLUMN destination_country TYPE VARCHAR(2);"
```

## V1 schema columns that were CHAR (for reference)
| Service | Table | Column | Was | Now |
|---|---|---|---|---|
| customer-kyc | customers | residency_country | CHAR(2) | VARCHAR(2) |
| transaction-monitoring | transactions | currency | CHAR(3) | VARCHAR(3) |
| transaction-monitoring | transactions | origin_country | CHAR(2) | VARCHAR(2) |
| transaction-monitoring | transactions | destination_country | CHAR(2) | VARCHAR(2) |

## Files Changed
- `services/customer-kyc/src/main/resources/db/migration/V3__fix_char_to_varchar.sql` (new)
- `services/transaction-monitoring/src/main/resources/db/migration/V3__fix_char_to_varchar.sql` (new)
