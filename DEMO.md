# AML Platform — Demo Guide

A three-service Anti-Money Laundering platform built as a senior-level Spring Boot + DDD + TDD reference. It doubles as a research testbed for **observability-driven anomaly detection**: every business event is fully traced, labelled, and metered, giving a clean feature stream for model training without touching the domain logic.

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                        HTTP Clients / UI                         │
└──────────┬───────────────────────┬───────────────────────────────┘
           │                       │
           ▼                       ▼
 ┌─────────────────┐    ┌──────────────────────┐    ┌─────────────────┐
 │  case-management│    │ transaction-monitoring│    │  customer-kyc   │
 │    :8080        │◄───│       :8081           │───►│     :8082       │
 │                 │    │                       │    │                 │
 │ Cases / workflow│    │ Rule engine / alerts  │    │ KYC / risk prof │
 └────────┬────────┘    └──────────┬────────────┘    └────────┬────────┘
          │  consumes               │  publishes               │  publishes
          │  AlertRaised            │  AlertRaised             │  CustomerRisk
          └─────────────────────────┴──────────────────────────┘
                               │   Kafka   │
                    ┌──────────┴───────────┴──────────┐
                    │  aml.alerts.events               │
                    │  aml.cases.events                │
                    │  aml.customers.events            │
                    └─────────────────────────────────┘
```

Each service owns its own PostgreSQL database — no shared schema, no cross-service JPA joins.

---

## The Three Services

### 1 · transaction-monitoring (Port 8081)

Ingests a transaction, runs it through the AML rule engine, and raises an alert if any rule fires.

**Rule engine** — four pluggable rules, all configurable:

| Rule ID  | Name              | What it detects                                                 |
|----------|-------------------|-----------------------------------------------------------------|
| AML-101  | High-Value        | Single transaction ≥ threshold (default £10,000)               |
| AML-102  | Velocity          | > N transactions in M hours (default: 5 in 1 h)               |
| AML-103  | Structuring       | Sum of sub-threshold amounts hits ceiling (smurfing / layering)|
| AML-104  | High-Risk Corridor| Destination country in sanctions-risk set (IR, KP, …)         |

Scores are combined with a **1 − ∏(1 − sᵢ)** formula — monotonically increasing, capped at 100, never linear-summed.

**Evaluate a transaction:**
```bash
curl -s -X POST http://localhost:8081/api/v1/transactions/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId":        "cust-1",
    "amount":            "12500.00",
    "currency":          "GBP",
    "originCountry":     "GB",
    "destinationCountry":"GB",
    "channel":           "FASTER_PAYMENTS"
  }'
```

**201 response (alert raised):**
```json
{
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "riskScore": 61,
  "alerted": true,
  "alertId": "9b1d4e2a-0c3f-4789-b12d-8a7c5e3f1d0b"
}
```

---

### 2 · case-management (Port 8080)

Listens to `aml.alerts.events` and automatically opens a Case. Investigators then work the case through its lifecycle.

**Case state machine:**
```
OPEN ──assign──► UNDER_INVESTIGATION ──escalate──► ESCALATED
                         │                              │
                         └──────────close──────────────►CLOSED (terminal)
```

**API — drive a case manually:**
```bash
# Open (also triggered automatically from Kafka)
curl -X POST http://localhost:8080/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d '{"alertId":"9b1d4e2a-...","customerId":"cust-1","riskScore":61}'

# Assign to investigator
curl -X POST http://localhost:8080/api/v1/cases/{id}/assign \
  -H 'Content-Type: application/json' \
  -d '{"investigatorId":"inv-007"}'

# Escalate
curl -X POST http://localhost:8080/api/v1/cases/{id}/escalate \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Customer is high-risk PEP"}'

# Close
curl -X POST http://localhost:8080/api/v1/cases/{id}/close \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"FALSE_POSITIVE"}'
```

**Idempotency** — the Kafka listener stores each `eventId` in `processed_events` and checks it atomically before opening a case. Duplicate deliveries are silently dropped; the dedup check and case insert share a single `@Transactional` boundary.

---

### 3 · customer-kyc (Port 8082)

Master data for customers — identity verification and risk profile. Its `GET /customers/{id}` endpoint sits on the hot path of every transaction evaluation.

**Lifecycle:**
```
PENDING ──verify──► IN_PROGRESS ──verify──► VERIFIED
                                 └──────────► REJECTED (terminal)
```

**Domain invariant:** a sanctioned customer is always `PROHIBITED` tier. Enforced both in code and by a database `CHECK` constraint.

```bash
# Onboard
curl -X POST http://localhost:8082/api/v1/customers \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-1","legalName":"Alice Smith","residencyCountry":"GB"}'

# KYC verify (call twice: PENDING → IN_PROGRESS → VERIFIED)
curl -X POST http://localhost:8082/api/v1/customers/cust-1/verify
curl -X POST http://localhost:8082/api/v1/customers/cust-1/verify

# Update risk profile
curl -X POST http://localhost:8082/api/v1/customers/cust-1/risk \
  -H 'Content-Type: application/json' \
  -d '{"tier":"HIGH","politicallyExposed":true,"sanctioned":false,"residencyCountry":"GB"}'

# Lookup (hot path — called by transaction-monitoring on every evaluation)
curl http://localhost:8082/api/v1/customers/cust-1
```

---

## Messaging: Transactional Outbox

All three services use the **outbox pattern** — no dual-write, no saga orchestrator, no distributed transactions.

```
┌──────────── same DB transaction ────────────────────────┐
│  1. UPDATE cases SET status = 'UNDER_INVESTIGATION'     │
│  2. INSERT INTO outbox (event_type, payload, ...)       │
└─────────────────────────────────────────────────────────┘
           ▼  (background scheduler, 500 ms poll, batch 50)
     Kafka producer  ──► topic
           ▼
     Consumer checks processed_events table
     If new → process + mark processed  (same transaction)
     If seen → drop silently
```

This ensures **exactly-once business semantics** even with at-least-once Kafka delivery.

---

## Test Strategy

```
                    ┌────────────────────┐
                    │  Contract (Pact)   │  ← wire-format guarantee
                    ├────────────────────┤
                    │   Integration      │  ← Testcontainers, real Postgres + Kafka
                    ├────────────────────┤
                    │  Application layer │  ← mocked ports, no Spring context
                    ├────────────────────┤
                    │  Domain (unit +    │  ← pure Java, milliseconds each
                    │  property-based)   │
                    └────────────────────┘
```

### Domain — Unit & Property-Based Tests (jqwik)

```java
@Property
void any_valid_case_is_created_in_OPEN_status(
        @ForAll @NotBlank String alertId,
        @ForAll @NotBlank String customerId,
        @ForAll @IntRange(min = 0, max = 100) int riskScore) {
    var c = Case.open(new OpenCaseCommand(alertId, customerId, riskScore));
    assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
}
```

Each property runs **1,000 randomly-generated inputs** per build.

### Architecture — ArchUnit (enforced as failing tests)

| Rule | What it prevents |
|------|-----------------|
| Domain ⊣ Spring | `@Component`, `@Entity`, `@Repository` inside the domain package |
| Domain ⊣ Infrastructure | Domain importing from `infrastructure.*` |
| Application ⊣ Infrastructure | Application service importing adapters |
| Hexagonal inward-only | Any outward dependency arrow across layer boundaries |

### Contract — Pact (Consumer-Driven)

```
case-management  ──generates──►  case-management-transaction-monitoring.json
                                          │
transaction-monitoring  ◄──verifies──────┘  (AlertRaisedProducerPactTest)

case-management  ──generates──►  case-management-customer-kyc.json
transaction-monitoring ─generates─► transaction-monitoring-customer-kyc.json
                                          │
customer-kyc  ◄──verifies────────────────┘  (CustomerLookupProviderPactTest)
```

The Maven reactor copies consumer pact files into the provider's `target/pacts/` before the test phase runs, so contract verification always uses the most recently generated pact.

### Mutation Testing — Pitest (≥ 85% kill rate required)

```bash
mvn org.pitest:pitest-maven:mutationCoverage -pl :case-management
# Report: services/case-management/target/pit-reports/index.html
```

---

## Running the Platform

### Prerequisites

| Tool | Min version | Notes |
|------|-------------|-------|
| Java | 21 | `java -version` |
| Maven | 3.9 | `mvn -version` |
| Docker Desktop | 24+ | Must be running for Testcontainers + image builds |
| kubectl | 1.28+ | bundled with Docker Desktop or `brew install kubectl` |
| Helm | 3.14+ | `brew install helm` |

---

## Option A — Local Development (docker-compose)

Everything in containers — one command brings up the full stack.

### 1 · Build images and start the full stack

```bash
# Run from the project root
docker compose -f infrastructure/docker-compose.yml up --build -d
```

First run builds all three JARs inside Docker (~3 min each); subsequent runs reuse the layer cache. This starts:

| Container | What | Host port |
|-----------|------|-----------|
| postgres-cases | case_management DB | 5432 |
| postgres-monitoring | transaction_monitoring DB | 5433 |
| postgres-kyc | customer_kyc DB | 5434 |
| kafka + zookeeper | messaging | 9092 (host) / 29092 (container) |
| prometheus | metrics scraper | 9090 |
| grafana | dashboards | 3000 |
| tempo | trace collector (OTLP) | 4318 |
| loki | log aggregation | 3100 |
| **customer-kyc** | KYC / risk profiles | **8082** |
| **transaction-monitoring** | Rule engine / alerts | **8081** |
| **case-management** | Cases / workflow | **8080** |

Wait until the services are healthy:
```bash
docker compose -f infrastructure/docker-compose.yml ps
# All three app containers should show "running" (Flyway runs migrations on startup)
```

### 2 · Run the tests

```bash
mvn verify
# CustomerLookupProviderPactTest is skipped if Docker is not running — not a failure.
```

### 3 · End-to-end walkthrough

```bash
# Onboard and verify a customer
curl -s -X POST http://localhost:8082/api/v1/customers \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-demo","legalName":"Bob Demo","residencyCountry":"GB"}'

curl -s -X POST http://localhost:8082/api/v1/customers/cust-demo/verify
curl -s -X POST http://localhost:8082/api/v1/customers/cust-demo/verify   # → VERIFIED

# Submit a high-value transaction — triggers AML-101
RESULT=$(curl -s -X POST http://localhost:8081/api/v1/transactions/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId":"cust-demo",
    "amount":"15000.00",
    "currency":"GBP",
    "originCountry":"GB",
    "destinationCountry":"GB",
    "channel":"FASTER_PAYMENTS"
  }')
echo $RESULT | jq .
ALERT_ID=$(echo $RESULT | jq -r '.alertId')

# case-management opens the case automatically via Kafka.
# If Kafka is not configured, open it manually:
CASE_ID=$(curl -s -X POST http://localhost:8080/api/v1/cases \
  -H 'Content-Type: application/json' \
  -d "{\"alertId\":\"$ALERT_ID\",\"customerId\":\"cust-demo\",\"riskScore\":61}" \
  | jq -r '.caseId')

# Work the case
curl -s -X POST http://localhost:8080/api/v1/cases/$CASE_ID/assign \
  -H 'Content-Type: application/json' \
  -d '{"investigatorId":"inv-007"}'

curl -s -X POST http://localhost:8080/api/v1/cases/$CASE_ID/close \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"CONFIRMED_SAR"}'
```

### 4 · Tear down

```bash
docker compose -f infrastructure/docker-compose.yml down -v   # -v removes volumes / DB data
```

---

## Option B — Full Kubernetes Deployment (Docker Desktop)

This is the research-grade path: services run as pods, Prometheus scrapes them, Tempo collects distributed traces across real network hops.

Enable Kubernetes in Docker Desktop → Settings → Kubernetes → Enable Kubernetes → Apply & Restart.

### Step 1 · Start local registry + observability stack

```bash
make cluster-up
```

Verify:
```bash
kubectl get nodes
# NAME             STATUS   ROLES           AGE
# docker-desktop   Ready    control-plane   Xm

kubectl cluster-info
```

### Step 2 · Observability stack

`make cluster-up` already installed the observability stack. It adds the Prometheus-community and Grafana Helm repos, then installs:

| Release | Chart | Namespace |
|---------|-------|-----------|
| kube-prom-stack | prometheus-community/kube-prometheus-stack | monitoring |
| tempo | grafana/tempo | monitoring |
| loki | grafana/loki-stack | monitoring |

Wait for all pods to be Ready:
```bash
kubectl get pods -n monitoring --watch
```

### Step 3 · Deploy data services (Postgres + Kafka)

The application Helm charts expect these in-cluster hostnames. Deploy via Bitnami charts:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

kubectl create namespace data

# One Postgres per bounded context
helm upgrade --install postgres-cases bitnami/postgresql -n data \
  --set auth.database=case_management \
  --set auth.username=case_mgmt \
  --set auth.password=dev \
  --set primary.service.clusterIP="" \
  --set fullnameOverride=postgres-cases

helm upgrade --install postgres-monitoring bitnami/postgresql -n data \
  --set auth.database=transaction_monitoring \
  --set auth.username=tx_mon \
  --set auth.password=dev \
  --set fullnameOverride=postgres-monitoring

helm upgrade --install postgres-kyc bitnami/postgresql -n data \
  --set auth.database=customer_kyc \
  --set auth.username=kyc \
  --set auth.password=dev \
  --set fullnameOverride=postgres-kyc

# Kafka
helm upgrade --install kafka bitnami/kafka -n data \
  --set replicaCount=1 \
  --set zookeeper.enabled=true \
  --set listeners.client.protocol=PLAINTEXT \
  --set fullnameOverride=kafka

kubectl get pods -n data --watch   # wait until all Running
```

### Step 4 · Build Docker images

All three Dockerfiles must be built from the **project root** so Docker can include the parent `pom.xml`. The build is two-stage: Maven compiles in a JDK image; only the JRE + JAR land in the final image.

```bash
# Build all three images (each takes ~3 min on first run; subsequent runs use layer cache)
docker build -f services/case-management/Dockerfile      -t localhost:5001/case-management:0.1.0      .
docker build -f services/transaction-monitoring/Dockerfile -t localhost:5001/transaction-monitoring:0.1.0 .
docker build -f services/customer-kyc/Dockerfile         -t localhost:5001/customer-kyc:0.3.0         .
```

**Why the build context is `.`** — the Dockerfiles copy the root `pom.xml` first, then each service's `pom.xml` and `src/`. Maven then resolves the multi-module parent correctly inside the container. A `dependency:go-offline` step runs before the source copy so that dependency downloads are cached as a separate Docker layer — iterative source edits don't re-download the internet.

### Step 5 · Push images to the local registry

```bash
docker push localhost:5001/case-management:0.1.0
docker push localhost:5001/transaction-monitoring:0.1.0
docker push localhost:5001/customer-kyc:0.3.0
```

Verify the images are in the registry:
```bash
curl -s http://localhost:5001/v2/_catalog | jq .
# {"repositories":["case-management","customer-kyc","transaction-monitoring"]}
```

### Step 6 · Deploy the application services

Each service has a Helm chart under `infrastructure/helm/{service}/`. The charts include Deployment, Service, HPA, PodDisruptionBudget, NetworkPolicy, and PrometheusServiceMonitor.

```bash
kubectl create namespace aml

# customer-kyc first — transaction-monitoring calls it on startup health check
helm upgrade --install customer-kyc infrastructure/helm/customer-kyc -n aml \
  --set env.DB_HOST=postgres-kyc.data.svc.cluster.local \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --wait

helm upgrade --install transaction-monitoring infrastructure/helm/transaction-monitoring -n aml \
  --set env.DB_HOST=postgres-monitoring.data.svc.cluster.local \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --wait

helm upgrade --install case-management infrastructure/helm/case-management -n aml \
  --set env.DB_HOST=postgres-cases.data.svc.cluster.local \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --wait
```

### Step 7 · Verify the deployment

```bash
# All pods Running
kubectl get pods -n aml
# NAME                                      READY   STATUS    RESTARTS
# case-management-6d8f9b-xxx                1/1     Running   0
# transaction-monitoring-7c4d5f-xxx         1/1     Running   0
# customer-kyc-5b9e8a-xxx (×3 replicas)    1/1     Running   0

# Check readiness probes passed
kubectl get endpoints -n aml

# Tail logs across all services
kubectl logs -n aml -l app.kubernetes.io/name=transaction-monitoring -f

# HPA status (starts at minReplicas, scales on CPU)
kubectl get hpa -n aml
```

### Step 8 · Hit the APIs via port-forward

```bash
kubectl port-forward -n aml svc/customer-kyc         8082:8082 &
kubectl port-forward -n aml svc/transaction-monitoring 8081:8081 &
kubectl port-forward -n aml svc/case-management       8080:8080 &
```

Then run the same `curl` walkthrough from Option A, Step 3.

### Tear down

```bash
make cluster-down   # removes observability stack + local registry
make k8s-delete     # deletes aml, aiops, data namespaces
```

---

## Observability

### Grafana — http://localhost:3000 (admin / admin)

| Dashboard | Key signals |
|-----------|------------|
| transaction-monitoring | Alert rate by rule (AML-101…104), rule combination heatmap, evaluation latency p95/p99 |
| case-management | Case throughput by status, open case age histogram, outbox lag |
| customer-kyc | Lookup latency p95/p99, onboarding/verification rate |
| All services | Outbox queue depth, Kafka consumer lag, JVM memory/GC |

### Distributed Traces — Grafana → Explore → Tempo

Every request is traced end-to-end. The application layer adds **span events per fired rule** and tags every span with `risk.score`, `risk.band`, `customer.id`, `channel` — making trace data directly usable as a labelled feature dataset for the anomaly detection model.

```
POST /transactions/evaluate
  └─ EvaluateTransactionUseCase
       ├─ span.event("rule.fired") rule.id=AML-101 contribution=61
       ├─ span.tag  risk.score=61  risk.band=HIGH  channel=FASTER_PAYMENTS
       └─ case.open
            └─ span.tag customer.id=cust-demo  alert.id=…
```

TraceQL query to find all HIGH-risk evaluations:

```
{service.name="transaction-monitoring"} | select(span.risk.band = "HIGH")
```

### Prometheus — http://localhost:9090

```promql
# Alert rate by rule
sum(rate(aml_alerts_raised_total[5m])) by (rule)

# Case open latency p99
histogram_quantile(0.99, rate(aml_case_open_seconds_bucket[5m]))

# KYC lookup latency (hot path) p95
histogram_quantile(0.95, rate(aml_kyc_lookup_seconds_bucket[5m]))

# Outbox lag (events waiting to be dispatched)
aml_outbox_pending_events
```

### Structured Logs — Grafana → Explore → Loki

Every log line carries `[traceId, spanId]` so Loki queries are joinable to Tempo traces:

```logql
{service="case-management"} | json | traceId = "abc123def456"
```

---

## Project Layout

```
aml-platform/
├── pom.xml                                # parent BOM + plugin management
├── DEMO.md
├── services/
│   ├── case-management/
│   │   ├── Dockerfile                     # build context: project root
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/…/
│   │       │   ├── domain/               # Case, CaseStatus, RiskScore, events
│   │       │   ├── application/          # CaseApplicationService, commands, ports
│   │       │   └── infrastructure/       # JPA, Kafka listener, REST, outbox
│   │       └── test/java/…/
│   │           ├── domain/               # CaseAggregateTest, CasePropertiesTest (jqwik)
│   │           ├── application/          # CaseApplicationServiceTest (mocked ports)
│   │           ├── infrastructure/       # AlertRaisedListenerTest (idempotency)
│   │           ├── contract/             # AlertRaisedConsumerPactTest, CustomerLookupConsumerPactTest
│   │           └── architecture/         # HexagonalArchitectureTest (ArchUnit)
│   ├── transaction-monitoring/            # same structure
│   └── customer-kyc/                      # same structure
└── infrastructure/
    ├── docker-compose.yml                 # local dev: Postgres × 3, Kafka, observability
    ├── helm/
    │   ├── case-management/              # Deployment, Service, HPA, PDB, NetworkPolicy, ServiceMonitor
    │   ├── transaction-monitoring/
    │   └── customer-kyc/
    ├── observability/
    │   ├── prometheus.yml
    │   └── tempo.yml
    └── scripts/
        ├── bootstrap.sh                  # create cluster + install observability stack
        └── teardown.sh                   # delete cluster
```

---

## SLO Targets (defined in Helm values)

| Service | Availability | Latency p95 | Latency p99 | Why |
|---------|-------------|-------------|-------------|-----|
| customer-kyc | 99.99% | 50 ms | 150 ms | Hot path of every transaction evaluation |
| transaction-monitoring | 99.95% | 100 ms | 250 ms | Synchronous path of payment authorisation |
| case-management | 99.9% | 200 ms | 500 ms | Async worker; not on the payment path |

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Hexagonal architecture | Domain logic has zero framework imports — testable in isolation, replaceable adapters |
| Database-per-service | No shared schema, no cross-service JPA joins, independent schema evolution |
| Outbox pattern | At-least-once Kafka delivery with exactly-once business semantics; no XA transactions |
| Processed-events dedup | Idempotent consumers without coordination — scales horizontally without contention |
| Property-based tests (jqwik) | Invariants proved for arbitrary inputs, not just hand-picked examples |
| ArchUnit in CI | Architecture constraints are checked code, not documentation that rots |
| Pact consumer-driven contracts | A breaking change in a producer is caught before it reaches integration |
| Mutation testing ≥ 85% | Guards against green tests that pass against deliberately wrong code |
| Full trace sampling (100%) | Research environment needs complete observability data for anomaly labelling |
| Span events per fired rule | The trace stream is the feature stream — no separate ETL pipeline needed |
| Value objects everywhere | `RiskScore`, `Money`, `CustomerId` carry validation and semantics — no primitive obsession |
| `sealed` domain events | Compiler-enforced exhaustive handling; no event type can be silently ignored |
| Docker build from project root | Multi-module Maven parent POM must be in the Docker build context |
| Local Docker registry | No external registry needed; `localhost:5001` push, same address in-cluster |
