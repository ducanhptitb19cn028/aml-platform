# Fix 09 — API Request Body Field Names (fullDemo.md)

## Problem
`fullDemo.md` had incorrect field names and paths for the transaction and case endpoints,
causing 400 Bad Request / 404 errors when following the demo walkthrough.

## Root Cause
The demo was written with assumed field names rather than verified against the actual
controller source code.

---

## Fix 1 — Transaction endpoint

### Wrong (original)
```bash
curl -s -X POST http://localhost:8081/api/v1/transactions \
  -d '{"customerId":"cust-001","amount":9500.00,"currency":"USD","counterparty":"ACME Corp","channel":"WIRE"}'
```

Problems:
- Path should be `/api/v1/transactions/evaluate` (not `/api/v1/transactions`)
- `counterparty` field does not exist on `EvaluateRequest`
- Missing required fields: `originCountry`, `destinationCountry`
- `amount` must be a **String** (e.g. `"9500.00"`), not a JSON number
- `WIRE` is not a valid channel — valid values: `SEPA`, `FASTER_PAYMENTS`, `SWIFT`, `CARD`, `CASH_DEPOSIT`, `CRYPTO_OFFRAMP`

### Correct
```bash
curl -s -X POST http://localhost:8081/api/v1/transactions/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId": "cust-001",
    "amount": "9500.00",
    "currency": "USD",
    "originCountry": "US",
    "destinationCountry": "GB",
    "channel": "SWIFT"
  }' | jq .
```

Source: `TransactionMonitoringController.java` → `EvaluateRequest` record

Response (HTTP 201 if alerted, 200 otherwise):
```json
{"transactionId": "...", "riskScore": 0.78, "alerted": true, "alertId": "..."}
```

---

## Fix 2 — Case endpoint

### Wrong (original)
```bash
curl -s http://localhost:8080/api/v1/cases?status=OPEN | jq .
```

Problems:
- `GET /api/v1/cases` does not exist in `CaseController.java`
- Cases are opened **automatically** via Kafka (transaction-monitoring → alert → case-management consumer)
- The controller only exposes POST endpoints

### Correct usage
Cases auto-open via Kafka. To verify: query the DB directly.
To manually open a case for testing:
```bash
curl -s -X POST http://localhost:8080/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{"alertId":"alert-xyz","customerId":"cust-001","riskScore":78}' | jq .
# Returns: {"id": "case-abc"}  HTTP 201
```

Case workflow endpoints:
- `POST /api/v1/cases/{id}/assign`   — body: `{"investigatorId": "..."}`
- `POST /api/v1/cases/{id}/escalate` — body: `{"reason": "..."}`
- `POST /api/v1/cases/{id}/close`    — body: `{"resolution": "..."}`

Source: `CaseController.java`

---

## Files Changed
- `fullDemo.md` — transaction endpoint path, field names, channel values, case section
