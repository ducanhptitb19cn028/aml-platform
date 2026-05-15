# Fix 11 — Postman Collection: Invalid `WIRE` Channel Value

## Problem
`POST /api/v1/transactions/evaluate` returned 400 Bad Request when run from
the Postman collection or the end-to-end flow script:
```json
{"status": 400, "error": "Bad Request", "path": "/api/v1/transactions/evaluate"}
```

## Root Cause
The Postman collection used `"channel": "WIRE"` for all transaction requests.
The `Channel` enum in `transaction-monitoring` has no `WIRE` value:
```java
public enum Channel {
    SEPA, FASTER_PAYMENTS, SWIFT, CARD, CASH_DEPOSIT, CRYPTO_OFFRAMP
}
```
Spring's `@Valid` binding fails to deserialise the unknown enum constant,
returning a 400 before the request reaches any business logic.

## Fix
**File:** `postman/AML-Platform.postman_collection.json`

| Request | Old value | New value |
|---|---|---|
| Evaluate Transaction – Low Risk (US→US) | `WIRE` | `CARD` |
| Evaluate Transaction – High Risk Cross-Border | `WIRE` | `SWIFT` |
| E2E 3 – Evaluate High-Risk Transaction | `WIRE` | `SWIFT` |

`SWIFT` is the correct channel for international wire transfers;
`CARD` is correct for domestic low-value transactions.

## Files Changed
- `postman/AML-Platform.postman_collection.json`
