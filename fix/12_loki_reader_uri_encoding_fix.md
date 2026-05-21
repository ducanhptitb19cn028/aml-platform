# Fix 12 — LokiReader: URI Encoding Breaks Kafka Signal Pipeline

## Problem
The telemetry-collector threw an `IllegalArgumentException` on every 30-second
Loki poll, so no log signals ever reached Kafka and the anomaly pipeline was
permanently starved:
```
ERROR LokiReader : Unexpected error polling Loki:
  Invalid character '{' for QUERY_PARAM in "{namespace="aml"}|json|level="ERROR""
java.lang.IllegalArgumentException: Invalid character '{' for QUERY_PARAM ...
  at HierarchicalUriComponents.verifyUriComponent(...)
  at LokiReader.queryLoki(LokiReader.java:55)
```
Dashboard showed **SSE live · 0 events received** indefinitely.

## Root Cause
`LokiReader.queryLoki()` built the Loki query URL with:
```java
UriComponentsBuilder.fromHttpUrl(...)
    .queryParam("query", "{namespace=\"aml\"}|json|level=\"ERROR\"")
    ...
    .build(true)   // ← tells Spring "already encoded" — skip encoding
    .toUri();
```
Passing `true` to `.build()` instructs Spring to treat the components as
already percent-encoded and to **validate without encoding**. The raw LogQL
query contains `{`, `}`, and `"` characters that are illegal in a URI query
component in their unencoded form, so validation threw immediately.

## Fix
**File:** `aiops/telemetry-collector/src/main/java/.../loki/LokiReader.java`

```java
// Before
.build(true)
.toUri();

// After
.build()
.encode()
.toUri();
```
`.build()` parses the components without assuming they are encoded;
`.encode()` then percent-encodes each component so `{` → `%7B`, `"` → `%22`,
producing a valid URL that Loki accepts.

### Rebuild required
```powershell
make image-aiops-collector
docker push localhost:5001/telemetry-collector:0.1.0
kubectl rollout restart deployment/telemetry-collector -n aiops
```

## Files Changed
- `aiops/telemetry-collector/src/main/java/com/alexbank/aiops/collector/infrastructure/loki/LokiReader.java`
