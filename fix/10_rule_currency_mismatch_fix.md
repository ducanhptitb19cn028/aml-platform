# Fix 10 — Rule Engine Currency Mismatch (USD vs GBP)

## Problem
`POST /api/v1/transactions/evaluate` with any non-GBP currency (e.g. `"currency":"USD"`)
returned 400 Bad Request:
```
"detail": "Cannot compare/combine different currencies: USD vs GBP"
```

## Root Cause
`RuleEngineConfig.java` creates a single shared `Money threshold` from:
```yaml
aml:
  rules:
    high-value:
      currency: GBP   # ← hardcoded GBP
      threshold: "10000"
```

Both `HighValueRule` and `StructuringRule` receive this GBP threshold.
When a USD transaction arrives, both rules call:
```java
tx.amount().isGreaterThanOrEqual(threshold)   // USD vs GBP → throws
```
`Money.requireSameCurrency()` throws `IllegalArgumentException` on currency mismatch.

## Fix

### 1. HighValueRule — skip on currency mismatch
**File:** `services/transaction-monitoring/src/main/java/.../rule/HighValueRule.java`

Added before the comparison:
```java
if (!amount.currency().equals(threshold.currency())) {
    return RuleVerdict.notFired(ID);
}
```

### 2. StructuringRule — skip on currency mismatch
**File:** `services/transaction-monitoring/src/main/java/.../rule/StructuringRule.java`

Added before the comparison:
```java
if (!tx.amount().currency().equals(threshold.currency())) {
    return RuleVerdict.notFired(ID);
}
```

This is the correct behavior: a GBP threshold rule simply does not apply to USD
transactions — it should not crash on them.

### 3. Change default currency to USD
**File:** `services/transaction-monitoring/src/main/resources/application.yml`

```yaml
aml:
  rules:
    high-value:
      currency: USD   # was: GBP
```

With USD as the default, the demo's USD transactions are evaluated by HighValueRule
and StructuringRule. The $9,500 transaction is below the $10,000 threshold but close
enough to combine with velocity/corridor rules.

### Rebuild required
```powershell
make image-monitoring
docker push localhost:5001/transaction-monitoring:0.1.0
kubectl rollout restart deployment/transaction-monitoring -n aml
```

## Files Changed
- `services/transaction-monitoring/src/main/java/.../rule/HighValueRule.java`
- `services/transaction-monitoring/src/main/java/.../rule/StructuringRule.java`
- `services/transaction-monitoring/src/main/resources/application.yml`
