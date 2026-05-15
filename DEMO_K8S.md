# AML Platform — Kubernetes Deployment Guide

Deploys all three services to **Docker Desktop Kubernetes** with the full observability stack (Prometheus, Grafana, Tempo, Loki). The cluster mirrors a production topology: real network hops between pods, HPA, PodDisruptionBudgets, and NetworkPolicies.

---

## Prerequisites

| Tool | Min version | Install |
|------|-------------|---------|
| Docker Desktop | 24+ (Kubernetes enabled) | https://docs.docker.com/get-docker/ |
| kubectl | 1.28+ | Bundled with Docker Desktop |
| Helm | 3.14+ | `winget install Helm.Helm` |
| Java + Maven | 21 / 3.9 | Only needed if you want `mvn verify` first |

Enable Kubernetes in Docker Desktop → Settings → Kubernetes → Enable Kubernetes → Apply & Restart.

All commands run from the **project root** (`D:\ResearchWithDrSatish\aml-platform`).

---

## Architecture Inside the Cluster

```
Namespace: aml
  ┌─────────────────────────────────────────────────────────────────┐
  │  customer-kyc (×3 pods)   transaction-monitoring (×2)          │
  │  case-management (×2)                                           │
  └───────────────────────────┬─────────────────────────────────────┘
                              │
Namespace: data               │
  ┌───────────────────────────▼─────────────────────────────────────┐
  │  postgres-cases   postgres-monitoring   postgres-kyc   kafka    │
  └─────────────────────────────────────────────────────────────────┘

Namespace: monitoring
  ┌─────────────────────────────────────────────────────────────────┐
  │  kube-prom-stack (Prometheus + Alertmanager + Grafana)          │
  │  tempo (distributed traces)   loki (log aggregation)            │
  └─────────────────────────────────────────────────────────────────┘
```

Registry: `localhost:5001` — a Docker registry container started by `make cluster-up`.

---

## Step 1 — Build Docker Images

All three Dockerfiles use the **project root** as build context so Maven can resolve the parent `pom.xml`. The build is two-stage: a JDK image compiles the JAR; only the JRE + JAR land in the final image.

```bash
# Run from project root. First run ~3 min per image; subsequent runs use layer cache.
docker build -f services/customer-kyc/Dockerfile          -t localhost:5001/customer-kyc:0.3.0         .
docker build -f services/transaction-monitoring/Dockerfile -t localhost:5001/transaction-monitoring:0.1.0 .
docker build -f services/case-management/Dockerfile        -t localhost:5001/case-management:0.1.0       .
```

Verify:
```bash
docker images | grep localhost:5001
# localhost:5001/customer-kyc           0.3.0   ...
# localhost:5001/transaction-monitoring 0.1.0   ...
# localhost:5001/case-management        0.1.0   ...
```

---

## Step 2 — Start Local Registry + Observability Stack

```bash
make cluster-up
```

What this does:
1. Switches kubectl context to `docker-desktop`
2. Starts a local Docker registry at `localhost:5001`
3. Adds Prometheus-community and Grafana Helm repos
4. Installs `kube-prometheus-stack`, `tempo`, and `loki-stack` into `namespace: monitoring`

Wait for the observability stack to be Ready before continuing:
```bash
kubectl get pods -n monitoring --watch
# Wait until all pods show 1/1 or 2/2 Running
```

---

## Step 3 — Push Images to the Local Registry

```bash
docker push localhost:5001/customer-kyc:0.3.0
docker push localhost:5001/transaction-monitoring:0.1.0
docker push localhost:5001/case-management:0.1.0
```

Verify the registry received them:
```bash
curl -s http://localhost:5001/v2/_catalog | python -m json.tool
# {"repositories": ["case-management", "customer-kyc", "transaction-monitoring"]}
```

---

## Step 4 — Deploy the Data Layer

One Postgres per bounded context + a single-broker Kafka. All in `namespace: data`.

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

kubectl create namespace data

# Postgres — one per service (database-per-service pattern)
helm upgrade --install postgres-cases bitnami/postgresql -n data \
  --set auth.database=case_management \
  --set auth.username=case_mgmt \
  --set auth.password=dev \
  --set fullnameOverride=postgres-cases \
  --wait

helm upgrade --install postgres-monitoring bitnami/postgresql -n data \
  --set auth.database=transaction_monitoring \
  --set auth.username=tx_mon \
  --set auth.password=dev \
  --set fullnameOverride=postgres-monitoring \
  --wait

helm upgrade --install postgres-kyc bitnami/postgresql -n data \
  --set auth.database=customer_kyc \
  --set auth.username=kyc \
  --set auth.password=dev \
  --set fullnameOverride=postgres-kyc \
  --wait

# Kafka — single broker, plaintext (dev cluster)
helm upgrade --install kafka bitnami/kafka -n data \
  --set replicaCount=1 \
  --set zookeeper.enabled=true \
  --set listeners.client.protocol=PLAINTEXT \
  --set fullnameOverride=kafka \
  --wait
```

Verify all pods are Running before proceeding:
```bash
kubectl get pods -n data
# NAME                        READY   STATUS    RESTARTS
# postgres-cases-0            1/1     Running   0
# postgres-monitoring-0       1/1     Running   0
# postgres-kyc-0              1/1     Running   0
# kafka-0                     1/1     Running   0
# kafka-zookeeper-0           1/1     Running   0
```

---

## Step 5 — Deploy the Application Services

Each service has a Helm chart under `infrastructure/helm/{service}/`. Charts include: Deployment, Service, HPA, PodDisruptionBudget, NetworkPolicy, PrometheusServiceMonitor.

Images are pulled from `localhost:5001` (the local registry started by `make cluster-up`).

```bash
kubectl create namespace aml

# customer-kyc first — every transaction evaluation calls it synchronously
helm upgrade --install customer-kyc infrastructure/helm/customer-kyc -n aml \
  --set env.DB_HOST=postgres-kyc.data.svc.cluster.local \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --set env.OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4318 \
  --wait

helm upgrade --install transaction-monitoring infrastructure/helm/transaction-monitoring -n aml \
  --set env.DB_HOST=postgres-monitoring.data.svc.cluster.local \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --set env.AML_KYC_BASE_URL=http://customer-kyc.aml.svc.cluster.local:8082 \
  --set env.OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4318 \
  --wait

helm upgrade --install case-management infrastructure/helm/case-management -n aml \
  --set env.DB_HOST=postgres-cases.data.svc.cluster.local \
  --set env.DB_NAME=case_management \
  --set env.DB_USER=case_mgmt \
  --set env.DB_PASS=dev \
  --set env.KAFKA_BOOTSTRAP=kafka.data.svc.cluster.local:9092 \
  --set env.OTEL_EXPORTER_OTLP_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4318 \
  --wait
```

---

## Step 6 — Verify the Deployment

```bash
# All pods Running
kubectl get pods -n aml
# NAME                                       READY   STATUS    RESTARTS
# customer-kyc-xxxxxxxxx-xxx (×3 replicas)   1/1     Running   0
# transaction-monitoring-xxxxxxxxx-xxx (×2)  1/1     Running   0
# case-management-xxxxxxxxx-xxx (×2)         1/1     Running   0

# Readiness probes passed (all pods appear in endpoints)
kubectl get endpoints -n aml

# HPA targets (CPU utilization / min-max replicas)
kubectl get hpa -n aml

# PodDisruptionBudgets
kubectl get pdb -n aml

# Tail logs from a service
kubectl logs -n aml -l app.kubernetes.io/name=customer-kyc --tail=40 -f

# Check Flyway ran migrations successfully
kubectl logs -n aml -l app.kubernetes.io/name=customer-kyc | grep -i flyway
```

---

## Step 7 — End-to-End Walkthrough

Port-forward all three services:

```bash
kubectl port-forward -n aml svc/customer-kyc          8082:8082 &
kubectl port-forward -n aml svc/transaction-monitoring 8081:8081 &
kubectl port-forward -n aml svc/case-management        8080:8080 &
```

Run the full AML flow:

```bash
# 1. Onboard a customer
curl -s -X POST http://localhost:8082/api/v1/customers \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"cust-k8s","legalName":"K8s Demo","residencyCountry":"GB"}'

# 2. KYC verify (call twice: PENDING → IN_PROGRESS → VERIFIED)
curl -s -X POST http://localhost:8082/api/v1/customers/cust-k8s/verify
curl -s -X POST http://localhost:8082/api/v1/customers/cust-k8s/verify

# 3. Confirm VERIFIED
curl -s http://localhost:8082/api/v1/customers/cust-k8s | python -m json.tool

# 4. Submit a high-value transaction — fires AML-101 (≥ £10,000)
RESULT=$(curl -s -X POST http://localhost:8081/api/v1/transactions/evaluate \
  -H 'Content-Type: application/json' \
  -d '{
    "customerId":        "cust-k8s",
    "amount":            "15000.00",
    "currency":          "GBP",
    "originCountry":     "GB",
    "destinationCountry":"GB",
    "channel":           "FASTER_PAYMENTS"
  }')
echo $RESULT | python -m json.tool
ALERT_ID=$(echo $RESULT | python -c "import sys,json; print(json.load(sys.stdin)['alertId'])")

# 5. case-management auto-opens a Case via the Kafka alert event.
#    List open cases:
curl -s http://localhost:8080/api/v1/cases | python -m json.tool

# 6. Assign the case
CASE_ID=$(curl -s http://localhost:8080/api/v1/cases \
  | python -c "import sys,json; cases=json.load(sys.stdin); print(cases[0]['caseId'])")

curl -s -X POST http://localhost:8080/api/v1/cases/$CASE_ID/assign \
  -H 'Content-Type: application/json' \
  -d '{"investigatorId":"inv-007"}'

# 7. Escalate (customer is high-value)
curl -s -X POST http://localhost:8080/api/v1/cases/$CASE_ID/escalate \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Transaction exceeds threshold, requires senior review"}'

# 8. Close with resolution
curl -s -X POST http://localhost:8080/api/v1/cases/$CASE_ID/close \
  -H 'Content-Type: application/json' \
  -d '{"resolution":"CONFIRMED_SAR"}'
```

---

## Step 8 — Observability

### Grafana — http://localhost:3000 (admin / prom-operator)

> Default password for kube-prometheus-stack is `prom-operator`, not `admin`.

| Dashboard | Key panels |
|-----------|-----------|
| transaction-monitoring | Alert rate by rule (AML-101…104), rule heatmap, p95/p99 latency |
| case-management | Case throughput by status, open case age histogram, outbox lag |
| customer-kyc | Lookup latency p95/p99, verification rate |
| Kubernetes / Compute | Pod CPU/memory, HPA replica count |

### Distributed Traces — Grafana → Explore → Tempo

Every inter-service call is traced end-to-end. Spans carry `risk.score`, `risk.band`, `customer.id`, `channel` — the trace stream is the feature stream for the anomaly detection model.

Find all HIGH-risk evaluations:
```
{service.name="transaction-monitoring"} | select(span.risk.band = "HIGH")
```

Find the full trace for a specific alert:
```
{service.name="transaction-monitoring"} | select(span.alert.id = "<ALERT_ID>")
```

### Prometheus — http://localhost:9090

```promql
# Alert rate by rule (last 5 min)
sum(rate(aml_alerts_raised_total[5m])) by (rule)

# KYC hot-path latency p95
histogram_quantile(0.95, rate(aml_kyc_lookup_seconds_bucket[5m]))

# Outbox backlog (events waiting to be dispatched to Kafka)
aml_outbox_pending_events

# Replica count per service (HPA)
kube_horizontalpodautoscaler_status_current_replicas{namespace="aml"}
```

### Logs — Grafana → Explore → Loki

Every log line carries `traceId` and `spanId` — joinable to Tempo traces:
```logql
{namespace="aml", app="transaction-monitoring"} | json | traceId = "<TRACE_ID>"
```

---

## Step 9 — HPA Scaling Demo

Generate load to trigger autoscaling of `customer-kyc` (target CPU 60%, min 3, max 20 replicas):

```bash
# Watch replica count in a second terminal
kubectl get hpa -n aml customer-kyc --watch

# Generate load (requires Apache Bench — or use hey / wrk)
ab -n 10000 -c 50 http://localhost:8082/api/v1/customers/cust-k8s
```

As CPU crosses 60%, the HPA scales up. The PodDisruptionBudget (`minAvailable: 2`) ensures at least 2 pods stay up during any rolling update or node drain.

---

## Step 10 — Teardown

```bash
# Kill the port-forwards first
kill %1 %2 %3

# Remove observability stack and local registry
make cluster-down

# Delete application namespaces
make k8s-delete
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ImagePullBackOff` | Image not pushed to registry before deploy | Re-run Step 3 |
| Pod stuck in `Init:0/1` | Postgres not Ready when app starts | Wait for `kubectl get pods -n data` to show all Running, then `kubectl rollout restart deployment -n aml` |
| Flyway error in logs | DB exists but schema missing | Check Bitnami Postgres auth matches `--set auth.*` values |
| `case-management` never opens cases | Kafka consumer lag or wrong bootstrap address | Check `kubectl logs -n aml -l app.kubernetes.io/name=case-management | grep -i kafka` |
| Grafana shows no data | ServiceMonitor not picked up | Verify `kubectl get servicemonitor -n aml`; kube-prom-stack must be installed before app services |
| `customer-kyc` returns 503 | KYC readiness probe failing (DB not migrated yet) | Check logs for Flyway output; wait ~30 s after postgres-kyc is Running |
