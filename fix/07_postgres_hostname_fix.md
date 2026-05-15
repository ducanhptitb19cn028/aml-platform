# Fix 07 — Bitnami PostgreSQL Service Naming Convention

## Problem
AML services crashed at startup with database connection errors. The services could not
reach their PostgreSQL instances.

## Root Cause
Bitnami's PostgreSQL Helm chart appends `-postgresql` to the release name when creating
the Kubernetes Service. The manifests were using the bare release name:

| Release Name | Expected (wrong) | Actual (correct) |
|---|---|---|
| `postgres-kyc` | `postgres-kyc` | `postgres-kyc-postgresql` |
| `postgres-monitoring` | `postgres-monitoring` | `postgres-monitoring-postgresql` |
| `postgres-cases` | `postgres-cases` | `postgres-cases-postgresql` |

## Fix

### Updated `DB_HOST` env vars in `infrastructure/k8s/aml-platform.yml`
```yaml
# customer-kyc
- name: DB_HOST
  value: postgres-kyc-postgresql.data.svc.cluster.local   # was: postgres-kyc.data...

# transaction-monitoring
- name: DB_HOST
  value: postgres-monitoring-postgresql.data.svc.cluster.local   # was: postgres-monitoring.data...

# case-management
- name: DB_HOST
  value: postgres-cases-postgresql.data.svc.cluster.local   # was: postgres-cases.data...
```

### Verify service names
```bash
kubectl get svc -n data
# postgres-kyc-postgresql           ClusterIP ...  5432/TCP
# postgres-kyc-postgresql-hl        ClusterIP None ...  5432/TCP  (headless)
# postgres-monitoring-postgresql     ClusterIP ...
# postgres-cases-postgresql          ClusterIP ...
```

## Files Changed
- `infrastructure/k8s/aml-platform.yml` — DB_HOST for all 3 AML services
