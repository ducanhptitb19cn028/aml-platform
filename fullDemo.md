# AML Platform + AIOps — Full Demo Guide

End-to-end walkthrough: deploy the AML platform on Docker Desktop Kubernetes,
deploy the AIOps observability pipeline, verify autonomous remediation,
and explore the React dashboard.

---

## Table of Contents

- [AML Platform + AIOps — Full Demo Guide](#aml-platform--aiops--full-demo-guide)
  - [Table of Contents](#table-of-contents)
  - [1. Architecture Overview](#1-architecture-overview)
  - [2. Prerequisites](#2-prerequisites)
  - [3. Project Layout](#3-project-layout)
  - [4. Step 1 — Start Local Registry \& Observability](#4-step-1--start-local-registry--observability)
  - [5. Step 2 — Build Container Images](#5-step-2--build-container-images)
    - [All at once](#all-at-once)
    - [Or individually](#or-individually)
  - [6. Step 3 — Push to Local Registry](#6-step-3--push-to-local-registry)
  - [7. Step 4 — Deploy the Data Layer](#7-step-4--deploy-the-data-layer)
  - [8. Step 5 — Deploy the AML Platform](#8-step-5--deploy-the-aml-platform)
  - [9. Step 6 — Deploy the AIOps Platform](#9-step-6--deploy-the-aiops-platform)
  - [10. Step 7 — Deploy the React Dashboard](#10-step-7--deploy-the-react-dashboard)
  - [10a. Step 7a — Wait for Ollama to Download Qwen2.5:3b](#10a-step-7a--wait-for-ollama-to-download-qwen253b)
  - [11. Step 8 — Create Kafka Topics](#11-step-8--create-kafka-topics)
  - [12. Step 9 — Verify Everything is Running](#12-step-9--verify-everything-is-running)
  - [13. Step 10 — Port-Forward \& Access](#13-step-10--port-forward--access)
  - [14. AML Platform Walkthrough](#14-aml-platform-walkthrough)
    - [Create a customer (KYC)](#create-a-customer-kyc)
    - [Submit a transaction](#submit-a-transaction)
    - [Check the resulting case](#check-the-resulting-case)
    - [Watch alerts flow via Kafka](#watch-alerts-flow-via-kafka)
  - [14a. API Testing with Postman](#14a-api-testing-with-postman)
    - [Import the collection and environment](#import-the-collection-and-environment)
    - [Run a folder](#run-a-folder)
    - [End-to-End Flow](#end-to-end-flow)
  - [14b. Load Testing with k6](#14b-load-testing-with-k6)
    - [Install k6](#install-k6)
    - [Baseline 50-VU test](#baseline-50-vu-test)
    - [RQ3 tracing overhead experiment](#rq3-tracing-overhead-experiment)
    - [Reading the output](#reading-the-output)
  - [15. AIOps Pipeline in Action](#15-aiops-pipeline-in-action)
    - [Check ml-engine health](#check-ml-engine-health)
    - [Trigger a synthetic anomaly (high CPU)](#trigger-a-synthetic-anomaly-high-cpu)
    - [Watch the pipeline propagate](#watch-the-pipeline-propagate)
  - [15a. LLM Engine in Action](#15a-llm-engine-in-action)
    - [Check LLM Engine health](#check-llm-engine-health)
  - [15b. AMLOps Agent — Chat Dashboard](#15b-amlops-agent--chat-dashboard)
    - [Check Ollama directly](#check-ollama-directly)
    - [Manually analyze an incident](#manually-analyze-an-incident)
    - [Add labeled training examples](#add-labeled-training-examples)
    - [Trigger LoRA fine-tuning](#trigger-lora-fine-tuning)
    - [Monitor training progress](#monitor-training-progress)
    - [Check training stats](#check-training-stats)
  - [16. Dashboard Tour \& Testing](#16-dashboard-tour--testing)
    - [Start the dashboard](#start-the-dashboard)
    - [Navbar pages](#navbar-pages)
    - [Verify each page loads](#verify-each-page-loads)
    - [Generate live data (trigger SSE updates)](#generate-live-data-trigger-sse-updates)
    - [Verify SSE stream directly](#verify-sse-stream-directly)
    - [Page-by-page reference](#page-by-page-reference)
  - [17. Observability](#17-observability)
    - [Grafana](#grafana)
    - [Prometheus](#prometheus)
    - [Distributed Traces (Tempo)](#distributed-traces-tempo)
    - [Logs (Loki)](#logs-loki)
  - [18. One-Shot Full Deploy](#18-one-shot-full-deploy)
  - [19. Teardown](#19-teardown)
    - [Delete application namespaces only](#delete-application-namespaces-only)
    - [Remove observability stack and registry](#remove-observability-stack-and-registry)
    - [Stop local Docker-compose infra](#stop-local-docker-compose-infra)
  - [20. Troubleshooting](#20-troubleshooting)
    - [Pod stuck in `ImagePullBackOff`](#pod-stuck-in-imagepullbackoff)
    - [Kafka consumer lag growing](#kafka-consumer-lag-growing)
    - [AML service not reaching Postgres](#aml-service-not-reaching-postgres)
    - [ml-engine returns 503](#ml-engine-returns-503)
    - [Dashboard shows "No data"](#dashboard-shows-no-data)
    - [HPA not scaling](#hpa-not-scaling)
  - [Quick Reference Card](#quick-reference-card)

---

## 1. Architecture Overview

```
┌─────────────────────────────── namespace: aml ────────────────────────────┐
│  customer-kyc :8082  ◄──►  transaction-monitoring :8081  ◄──►  case-management :8080  │
│     HPA 3-20             HPA 2-15                         HPA 2-10        │
└────────────────────────────────────────────────────────────────────────────┘
           │  metrics / traces / logs (Kafka)
           ▼
┌─────────────────────────────── namespace: aiops ───────────────────────────┐
│  telemetry-collector :9001  →  stream-processor :9002  →  ml-engine :8000  │
│                                                              ↓              │
│  feedback-service :9006  ←  remediation-engine :9004  ←  decision-engine :9003 │
│                                      ↑                                      │
│                              alerting-service :9005 (SSE /api/v1/stream)   │
│                                      ↓                                      │
│                              ollama :11434  (Qwen2.5:3b, quantized local)   │
│                              llm-engine :8001  (Ollama inference + LoRA)    │
│                                      ↓                                      │
│                              React Dashboard :80  (real-time SSE + LLM)    │
└────────────────────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────── namespace: data ────────────────────────────┐
│  postgres-kyc  │  postgres-monitoring  │  postgres-cases  │  kafka          │
└────────────────────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────── namespace: monitoring ──────────────────────┐
│  Prometheus  │  Grafana  │  Tempo (traces)  │  Loki (logs)                 │
└────────────────────────────────────────────────────────────────────────────┘
```

**AIOps pipeline stages:**

| Stage | Service | Kafka topic(s) |
|-------|---------|----------------|
| Collect | telemetry-collector | → `aiops.telemetry.*` |
| Normalise | stream-processor | → `aiops.features` |
| Score | ml-engine (Python IsolationForest) | → `aiops.incidents` |
| Decide | decision-engine | → `aiops.decisions` |
| Automate | remediation-engine | → `aiops.actions` |
| Learn | feedback-service | → `aiops.outcomes` |
| **Explain** | **llm-engine (Qwen2.5:3b via Ollama)** | `aiops.incidents` → `aml.llm.analysis` |

**Real-time data flow:**

```
Kafka aiops.incidents
  └→ alerting-service (Java)
       ├→ IncidentStore (in-memory)
       └→ SSE broadcast (/api/v1/stream)
            └→ React dashboard (EventSource)
                 ├→ LiveAnomalyChart (real-time time-series)
                 └→ IncidentTable (live updates)

Kafka aiops.incidents
  └→ llm-engine (Python)
       ├→ Ollama API → qwen2.5:3b (local, quantized)
       ├→ Kafka aml.llm.analysis
       └→ GET /v1/analyses → LlmAnalysisPanel (dashboard)

Kafka aiops.outcomes
  └→ llm-engine
       └→ auto-collect labeled examples → POST /v1/train/start
            └→ LoRA fine-tuning (Qwen2.5-0.5B-Instruct via PEFT)
                 └→ /tmp/qwen-lora-adapter (used for future inference)
```

---

## 2. Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker Desktop | ≥ 24 (Kubernetes enabled) | https://docs.docker.com/get-docker/ |
| kubectl | ≥ 1.28 | bundled with Docker Desktop |
| Helm | ≥ 3.14 | `brew install helm` / `choco install kubernetes-helm` |
| Java | 21 (for local Maven builds) | `brew install openjdk@21` |
| Node.js | ≥ 20 (for local dashboard dev) | `brew install node` |

Enable Kubernetes in Docker Desktop → Settings → Kubernetes → Enable Kubernetes → Apply.

Verify:

```bash
docker info
kubectl version --client
kubectl config current-context   # should print: docker-desktop
helm version
```

---

## 3. Project Layout

```
aml-platform/
├── services/                   # AML — Spring Boot 3 services
│   ├── case-management/        #   port 8080
│   ├── transaction-monitoring/ #   port 8081
│   └── customer-kyc/           #   port 8082
├── aiops/                      # AIOps — Spring Boot 3 services
│   ├── telemetry-collector/    #   port 9001
│   ├── stream-processor/       #   port 9002
│   ├── decision-engine/        #   port 9003
│   ├── remediation-engine/     #   port 9004
│   ├── alerting-service/       #   port 9005
│   └── feedback-service/       #   port 9006
├── ml/
│   └── ml-engine/              # Python FastAPI — port 8000
├── dashboard/                  # React 18 + Vite + Tailwind
├── k6/                         # Load testing (k6)
│   ├── load-test.js            #   50-VU baseline (paper §4.1)
│   ├── rq3-overhead.js         #   RQ3 tracing overhead experiment
│   ├── run-rq3.ps1             #   Automation: all 4 sampling-rate runs
│   └── lib/
│       ├── data.js             #     Test data generators
│       └── workflow.js         #     HTTP wrappers + per-service metrics
├── infrastructure/
│   ├── docker-compose.yml      # Local dev: AML infra
│   ├── aiops/
│   │   └── docker-compose.yml  # Local dev: AIOps supplementary
│   ├── k8s/
│   │   ├── aml-platform.yml    # K8s: AML namespace
│   │   ├── aiops.yml           # K8s: AIOps namespace + RBAC
│   │   └── dashboard.yml       # K8s: React dashboard
│   └── scripts/
│       ├── bootstrap.ps1       # Cluster + observability setup (PowerShell)
│       └── teardown.ps1        # Full cluster teardown (PowerShell)
└── Makefile                    # All commands in one place
```

---

## 4. Step 1 — Start Local Registry & Observability

> **Windows note:** `bootstrap.ps1` calls `helm.exe` to install the observability stack.
> If helm is blocked by an Application Control policy, run the script **directly from an
> elevated (Admin) PowerShell terminal** — `make cluster-up` spawns a subprocess that
> inherits the block:
> ```powershell
> powershell -ExecutionPolicy Bypass -File infrastructure/scripts/bootstrap.ps1
> ```

```bash
make cluster-up
```

This runs `infrastructure/scripts/bootstrap.ps1` which:

- Switches kubectl context to `docker-desktop`
- Starts a local Docker registry container at `localhost:5001`
- Installs the `kube-prometheus-stack` (Prometheus + Grafana + Alertmanager)
- Installs Loki + Tempo for log and trace aggregation

Verify the cluster:

```bash
kubectl get nodes
# NAME             STATUS   ROLES           AGE
# docker-desktop   Ready    control-plane   1m
```

---

## 5. Step 2 — Build Container Images

### All at once

```bash
make image-aml image-aiops image-dashboard
```

### Or individually

```bash
# AML services (build context = project root — required for multi-module Maven)
make image-cases        # → localhost:5001/case-management:0.1.0
make image-monitoring   # → localhost:5001/transaction-monitoring:0.1.0
make image-kyc          # → localhost:5001/customer-kyc:0.3.0

# AIOps services (build context = aiops/)
make image-aiops-collector    # → localhost:5001/telemetry-collector:0.1.0
make image-aiops-stream       # → localhost:5001/stream-processor:0.1.0
make image-aiops-ml           # → localhost:5001/ml-engine:0.1.0
# Fix applied: transformers bumped 4.45.2→4.46.3 (trl==0.12.0 requires >=4.46.0)
make image-aiops-llm          # → localhost:5001/llm-engine:0.1.0
make image-aiops-decision     # → localhost:5001/decision-engine:0.1.0
make image-aiops-remediation  # → localhost:5001/remediation-engine:0.1.0
make image-aiops-alerting     # → localhost:5001/alerting-service:0.1.0
make image-aiops-feedback     # → localhost:5001/feedback-service:0.1.0

# React dashboard (build context = dashboard/)
# Fix applied: tsconfig.json target/lib bumped to ES2022 so Array.at() compiles
make image-dashboard    # → localhost:5001/aiops-dashboard:0.1.0
```

> **Note:** AML Dockerfiles use the project root as build context so the
> Maven reactor can find all sibling `pom.xml` files during
> `dependency:go-offline`. Do not use `services/<name>` as the context.

---

## 6. Step 3 — Push to Local Registry

```bash
make push-aml
make push-aiops
make push-dashboard
```

Verify images are in the registry:

```bash
curl http://localhost:5001/v2/_catalog
# {"repositories":["alerting-service","aiops-dashboard","case-management",
#   "customer-kyc","decision-engine","feedback-service","ml-engine",
#   "remediation-engine","stream-processor","telemetry-collector",
#   "transaction-monitoring"]}
```

> **Note:** Docker Desktop Kubernetes shares the Docker daemon, so images
> pushed to `localhost:5001` are accessible from inside the cluster.
> The registry container (`aml-registry`) is started by `make cluster-up`.

---

## 7. Step 4 — Deploy the Data Layer

```bash
make k8s-data
```

This applies static manifests into the `data` namespace (no Helm required):

- `postgres-kyc` — PostgreSQL 15 for `customer_kyc` database (`infrastructure/k8s/postgres-dev.yml`)
- `postgres-monitoring` — PostgreSQL 15 for `transaction_monitoring` database
- `postgres-cases` — PostgreSQL 15 for `case_management` database
- `kafka` — Single-node Kafka broker (`infrastructure/k8s/kafka-dev.yml`)

Service names match the Bitnami chart convention (`postgres-kyc-postgresql`, etc.) so
`aml-platform.yml` needs no changes.

Wait for all pods to be ready:

```powershell
kubectl get pods -n data -w
# postgres-kyc-0             1/1   Running
# postgres-monitoring-0      1/1   Running
# postgres-cases-0           1/1   Running
# kafka-0                    1/1   Running
```

---

## 8. Step 5 — Deploy the AML Platform

```bash
make k8s-aml
# kubectl apply -f infrastructure/k8s/aml-platform.yml
```

**What gets created in the `aml` namespace:**

| Resource | Name | Details |
|----------|------|---------|
| Namespace | `aml` | — |
| Deployment | `customer-kyc` | 3 replicas, port 8082 |
| Service | `customer-kyc` | ClusterIP :8082 |
| HPA | `customer-kyc` | min 3 / max 20 / CPU 60% |
| PodDisruptionBudget | `customer-kyc` | minAvailable 2 |
| Deployment | `transaction-monitoring` | 2 replicas, port 8081 |
| Service | `transaction-monitoring` | ClusterIP :8081 |
| HPA | `transaction-monitoring` | min 2 / max 15 / CPU 65% |
| PodDisruptionBudget | `transaction-monitoring` | minAvailable 1 |
| Deployment | `case-management` | 2 replicas, port 8080 |
| Service | `case-management` | ClusterIP :8080 |
| HPA | `case-management` | min 2 / max 10 / CPU 70% |
| PodDisruptionBudget | `case-management` | minAvailable 1 |

Wait for rollout:

```bash
kubectl rollout status deployment/customer-kyc -n aml
kubectl rollout status deployment/transaction-monitoring -n aml
kubectl rollout status deployment/case-management -n aml
```

---

## 9. Step 6 — Deploy the AIOps Platform

```bash
make k8s-aiops
# kubectl apply -f infrastructure/k8s/aiops.yml
```

**What gets created in the `aiops` namespace:**

| Resource | Name | Details |
|----------|------|---------|
| Namespace | `aiops` | — |
| ServiceAccount | `remediation-engine` | For K8s API access |
| ClusterRole | `aiops-remediation-role` | Edit HPAs, Deployments, Ingresses |
| ClusterRoleBinding | `aiops-remediation-binding` | Binds SA to role |
| ConfigMap | `aiops-common-env` | Shared env vars (Kafka, Prometheus, Loki, Tempo, MLflow, ES) |
| Deployment + Service | `telemetry-collector` | :9001 — scrapes Prometheus, tails Loki |
| Deployment + Service | `stream-processor` | :9002 — Kafka Streams normalisation |
| Deployment + Service | `ml-engine` | :8000 — Python IsolationForest scoring |
| Deployment + Service | `decision-engine` | :9003 — blast-radius × confidence policy |
| Deployment + Service | `remediation-engine` | :9004 — fabric8 K8s actuator (namespace-aware: `AML_NAMESPACE=aml`, `AIOPS_NAMESPACE=aiops`) |
| Deployment + Service | `alerting-service` | :9005 — REST API + Slack alerts + Prometheus health fetcher |
| Deployment + Service | `feedback-service` | :9006 — outcome labelling → MLflow |
| Deployment + Service | `mlflow` | :5000 — experiment tracking |
| Deployment + Service | `elasticsearch` | :9200 — hot signal storage |
| ServiceMonitor ×8 | per-service | Prometheus Operator scrape configs for all AIOps services |

> All 6 Java Spring Boot AIOps services expose `/actuator/prometheus` and carry
> `OTEL_EXPORTER_OTLP_ENDPOINT` env vars for traces.  The two Python services
> (`ml-engine`, `llm-engine`) expose `/metrics` via `prometheus-fastapi-instrumentator`.

---

## 10. Step 7 — Deploy the React Dashboard

```bash
make k8s-dashboard
# kubectl apply -f infrastructure/k8s/dashboard.yml
```

Creates `aiops-dashboard` Deployment + ClusterIP Service in the `aiops`
namespace. The Nginx container serves the pre-built React bundle on port 80.

The dashboard now includes:
- **Real-time SSE** feed from alerting-service (no polling delay)
- **Live Anomaly Chart** — rolling 30-second bucket time-series per service
- **LLM Analysis Panel** — Claude-powered explanations for each incident

---

## 10a. Step 7a — Wait for Ollama to Download Qwen2.5:3b

The Ollama pod pulls the Qwen2.5:3b model (~2 GB) on first startup.
Watch the pull progress:

```powershell
# Check pull progress
kubectl logs -n aiops deployment/ollama -f

# Wait until the readiness probe passes
kubectl get pod -n aiops -l app.kubernetes.io/name=ollama -w
# STATUS goes: Running (not ready) → Running (1/1 Ready)

# Or force a manual pull inside the running pod
make k8s-ollama-pull
```

The model is cached on a 10 Gi PVC — subsequent pod restarts load instantly.

---

## 11. Step 8 — Create Kafka Topics

```bash
make k8s-topics
```

Creates 8 topics inside the running Kafka pod:

| Topic | Partitions | Purpose |
|-------|-----------|---------|
| `aiops.telemetry.metrics` | 6 | Raw Prometheus metric samples |
| `aiops.telemetry.traces` | 6 | OpenTelemetry span events |
| `aiops.telemetry.logs` | 6 | Loki log stream entries |
| `aiops.features` | 3 | Normalised feature vectors |
| `aiops.incidents` | 3 | Anomaly + RCA payloads |
| `aiops.decisions` | 3 | Remediation decisions |
| `aiops.actions` | 3 | Executed action audit log |
| `aiops.outcomes` | 3 | Remediation outcome labels |
| `aiops.alerts` | 3 | Fired alert events from alerting-service |
| `aml.llm.analysis` | 3 | LLM incident analysis results |

---

## 12. Step 9 — Verify Everything is Running

```bash
make k8s-status
```

Expected output:

```
── aml namespace ──────────────────────────────────────────────
NAME                                    READY   STATUS    RESTARTS
pod/case-management-xxx                 1/1     Running   0
pod/transaction-monitoring-xxx          1/1     Running   0
pod/customer-kyc-xxx (×3)              1/1     Running   0

NAME                                    TARGETS   MINPODS   MAXPODS   REPLICAS
horizontalpodautoscaler/customer-kyc    18%/60%   3         20        3

── aiops namespace ────────────────────────────────────────────
NAME                                    READY   STATUS    RESTARTS
pod/telemetry-collector-xxx             1/1     Running   0
pod/stream-processor-xxx                1/1     Running   0
pod/ml-engine-xxx                       1/1     Running   0
pod/decision-engine-xxx                 1/1     Running   0
pod/remediation-engine-xxx              1/1     Running   0
pod/alerting-service-xxx                1/1     Running   0
pod/feedback-service-xxx                1/1     Running   0
pod/aiops-dashboard-xxx                 1/1     Running   0
pod/mlflow-xxx                          1/1     Running   0
pod/elasticsearch-xxx                   1/1     Running   0
```

---

## 13. Step 10 — Port-Forward & Access

Open four terminals:

**Terminal 1 — AML services**

```bash
make pf-aml
# case-management      → http://localhost:8080
# transaction-monitoring → http://localhost:8081
# customer-kyc         → http://localhost:8082
```

**Terminal 2 — AIOps services**

```bash
make pf-aiops
# alerting-service REST API → http://localhost:9005
# alerting-service SSE      → http://localhost:9005/api/v1/stream
# ml-engine health          → http://localhost:8000/v1/health
# llm-engine health         → http://localhost:8001/v1/health
# llm-engine analyses       → http://localhost:8001/v1/analyses
# llm-engine stats          → http://localhost:8001/v1/stats
```

**Terminal 3 — Dashboard**

```bash
make pf-dashboard
# React dashboard → http://localhost:3001
```

**Terminal 4 — Observability**

```bash
make pf-obs
# Grafana    → http://localhost:3000  (admin / eJxxkpooypo7yjecjesmTGn6dSwSh7gEBS9Rz7bn)
# Prometheus → http://localhost:9090
```

---

## 14. AML Platform Walkthrough

### Create a customer (KYC)

```bash
curl.exe -s -X POST http://localhost:8082/api/v1/customers \-H 'Content-Type: application/json' \-d '{
    "customerId": "cust-001",
    "legalName": "Alice Wonderland",
    "residencyCountry": "US"
  }' | jq .
```

Expected response:

```json
{
  "id": "cust-001"
}
```

### Submit a transaction

```bash
curl.exe -s -X POST http://localhost:8081/api/v1/transactions/evaluate \-H 'Content-Type: application/json' \-d '{
    "customerId": "cust-001",
    "amount": "9500.00",
    "currency": "USD",
    "originCountry": "US",
    "destinationCountry": "GB",
    "channel": "SWIFT"
  }'
```

Valid `channel` values: `SEPA`, `FASTER_PAYMENTS`, `SWIFT`, `CARD`, `CASH_DEPOSIT`, `CRYPTO_OFFRAMP`

A transaction near the $10,000 structuring threshold produces (HTTP 201 if alerted, 200 otherwise):

```json
{
  "transactionId": "txn-abc123",
  "riskScore": 0.78,
  "alerted": true,
  "alertId": "alert-xyz"
}
```

### Check the resulting case

Cases are opened **automatically** — when `transaction-monitoring` flags an alert it publishes to the `aml.alerts.raised` Kafka topic; `case-management` consumes that message and calls its own `POST /api/v1/cases` internally.

Verify via the database:

```powershell
kubectl exec -n data postgres-cases-0 -- psql -U case_mgmt -d case_management `
  -c "SELECT id, customer_id, risk_score, status FROM cases ORDER BY created_at DESC LIMIT 5;"
```

To manually open a case (for testing without the full Kafka flow):

```bash
curl -s -X POST http://localhost:8080/api/v1/cases \-H 'Content-Type: application/json' \-d '{
    "alertId": "alert-xyz",
    "customerId": "cust-001",
    "riskScore": 78
  }' .
# Returns: {"id": "case-abc"}  HTTP 201
```

To work a case after it is opened:

```bash
# Assign to an investigator
curl -s -X POST http://localhost:8080/api/v1/cases/case-abc/assign \
  -H 'Content-Type: application/json' \
  -d '{"investigatorId": "inv-001"}' -o /dev/null -w "%{http_code}"
# 204 No Content

# Escalate
curl -s -X POST http://localhost:8080/api/v1/cases/case-abc/escalate \
  -H 'Content-Type: application/json' \
  -d '{"reason": "Large cross-border transfer"}' -o /dev/null -w "%{http_code}"

# Close
curl -s -X POST http://localhost:8080/api/v1/cases/case-abc/close \
  -H 'Content-Type: application/json' \
  -d '{"resolution": "Confirmed legitimate wire transfer"}' -o /dev/null -w "%{http_code}"
```

### Watch alerts flow via Kafka

```bash
kubectl exec -n data kafka-0 \
  -- /opt/kafka/bin/kafka-console-consumer.sh \
     --bootstrap-server localhost:9092 \
     --topic aiops.telemetry.metrics \
     --from-beginning \
     --max-messages 5
```

---

## 14a. API Testing with Postman

A ready-made Postman collection lives in `postman/` and covers all six services
with test assertions and automatic variable chaining (IDs from one request are
saved and reused by the next).

| File | Purpose |
|------|---------|
| `postman/AML-Platform.postman_collection.json` | 36 requests across 7 folders + End-to-End Flow |
| `postman/AML-Platform.postman_environment.json` | Pre-wired localhost ports for every service |

### Import the collection and environment

1. Open Postman → **Import** (top-left).
2. Drag-and-drop **both** files from `postman/` onto the import dialog, or click
   **Choose Files** and select them together.
3. After import, open the **Environments** tab (left sidebar) and select
   **AML Platform – Local**.

> Port-forwards must be running before sending any request:
> ```bash
> make pf-aml    # terminals for :8080 :8081 :8082
> make pf-aiops  # terminals for :8000 :8001 :9005
> ```

### Run a folder

Right-click any folder → **Run folder** → **Run AML Platform**.

The **Collection Runner** executes requests in order, shows pass/fail for every
`pm.test()` assertion, and carries collection variables (`customerId`, `caseId`,
`alertId`, `incidentId`) between requests automatically.

Individual folders and what they cover:

| Folder | Requests | What it tests |
|--------|----------|---------------|
| Health Checks | 6 | Liveness for all 6 services |
| Customer KYC | 6 | Onboard, get, 404, verify, risk HIGH, risk SANCTIONED |
| Transaction Monitoring | 3 | Low-risk, high-risk cross-border, missing-field 400 |
| Case Management | 5 | Open, bad riskScore 400, assign, escalate, close |
| AIOps – Alerting Service | 6 | Incidents, incidents+limit, remediations, outcomes, summary, service health |
| ML Engine | 4 | Score single, score batch (3 services), empty-features 400, model update |
| LLM Engine | 7 | Stats, analyze, add training example, status, start/stop fine-tuning, recent analyses |
| **End-to-End Flow** | **8** | Full lifecycle — see below |

### End-to-End Flow

Run the **End-to-End Flow** folder in order to exercise the complete AML
lifecycle in one pass:

| Step | Request | Variable saved |
|------|---------|----------------|
| E2E 1 | Onboard Customer (Dragon Shell Corp, CN) | `customerId` |
| E2E 2 | Verify Customer | — |
| E2E 3 | Evaluate High-Risk Transaction (CN→KP, $120k WIRE) | `alertId` |
| E2E 4 | Open Case (riskScore 92) | `caseId` |
| E2E 5 | Score Incident via ML Engine | `incidentId` |
| E2E 6 | Analyze via LLM Engine | asserts `amlRisk` is HIGH or CRITICAL |
| E2E 7 | Assign Case to senior-analyst-01 | — |
| E2E 8 | Verify Service Health (post-load) | — |

Variables chain automatically — no manual copy-paste needed between steps.

---

## 14b. Load Testing with k6

k6 scripts live in `k6/` and reproduce the **50-VU synthetic AML workload**
described in the paper (§4.1). Each VU iteration runs the full compliance
lifecycle: onboard customer → verify → submit 3–5 transactions → open / assign /
escalate / close a case when an alert fires.

### Install k6

```powershell
# Windows (winget — no admin required)
winget install k6

# Windows (Chocolatey)
choco install k6

# Verify
k6 version
# k6 v0.54.0 (...)
```

> Download the MSI installer from https://k6.io/docs/getting-started/installation/
> if neither package manager is available.

### Baseline 50-VU test

**Port-forwards must be running first** (`make pf-aml` in a separate terminal).

```powershell
# From the project root
k6 run `
  -e KYC_URL=http://localhost:8082 `
  -e TXN_URL=http://localhost:8081 `
  -e CASE_URL=http://localhost:8080 `
  k6/load-test.js
```

Or from inside the `k6/` directory (URLs default to localhost):

```powershell
cd k6
k6 run load-test.js
```

To save the full JSON output for analysis:

```powershell
k6 run --out json=results/baseline.json k6/load-test.js
```

**What it runs:**

| Stage | Duration | VUs |
|-------|----------|-----|
| Ramp-up | 30 s | 0 → 50 |
| Steady state | 5 min | 50 |
| Ramp-down | 30 s | 50 → 0 |

**SLO thresholds enforced (test fails if breached):**

| Metric | Threshold |
|--------|-----------|
| `http_req_duration` (all services) | p(95) < 500 ms |
| `kyc_request_duration` | p(95) < 500 ms |
| `txn_request_duration` | p(95) < 500 ms |
| `case_request_duration` | p(95) < 500 ms |
| `aml_error_rate` | rate < 1 % |
| `http_req_failed` | rate < 1 % |

**Transaction scenario mix (matches paper AML rule coverage):**

| Scenario | Weight | AML Rule |
|----------|--------|----------|
| Clean | 55 % | — |
| High-value (> £15 k) | 20 % | AML-101 |
| High-risk corridor | 10 % | AML-404 |
| Structuring (£9 000–£9 999) | 10 % | AML-303 |
| Crypto off-ramp | 5 % | AML-404 |

### RQ3 tracing overhead experiment

RQ3 measures p95 latency and throughput across four `TRACE_SAMPLE_RATE` values
(0 %, 10 %, 50 %, 100 %). The `run-rq3.ps1` script automates all four runs:
it patches the `aiops-config` ConfigMap, triggers a rolling restart of AML
deployments, waits 60 s for Prometheus to establish a clean baseline, then runs
k6 and saves results to `k6/results/`.

```powershell
# Run all four rates automatically (from k6/ directory)
cd k6
.\run-rq3.ps1

# Override base URLs if port-forwarding to non-default ports
.\run-rq3.ps1 `
  -KycUrl  http://localhost:8082 `
  -TxnUrl  http://localhost:8081 `
  -CaseUrl http://localhost:8080 `
  -OutputDir results
```

To run a single rate manually:

```powershell
# First patch the ConfigMap
kubectl patch configmap aiops-config -n aiops --type merge `
  -p '{"data":{"TRACE_SAMPLE_RATE":"0.5"}}'

# Restart AML pods to pick up the new env value
kubectl rollout restart deployment -n aml
kubectl rollout status deployment -n aml --timeout=120s

# Wait for Prometheus to scrape a clean baseline
Start-Sleep -Seconds 60

# Run k6 — SAMPLE_RATE is a label only (actual sampling is set via ConfigMap above)
k6 run `
  -e SAMPLE_RATE=0.5 `
  -e KYC_URL=http://localhost:8082 `
  -e TXN_URL=http://localhost:8081 `
  -e CASE_URL=http://localhost:8080 `
  --out json=results/rq3_0_5.json `
  k6/rq3-overhead.js
```

Each run: 20 s ramp → **3 min steady state** (50 VUs, 3 transactions/iteration) → 10 s drain.

### Reading the output

k6 prints a summary table at the end of every run. Key lines to check:

```
✓ kyc onboard → 201
✓ txn evaluate → 200|201
✓ case open → 201

http_req_duration.............: avg=42ms  p(90)=110ms  p(95)=148ms
  { service:kyc }.............: avg=18ms  p(95)=52ms
  { service:txn }.............: avg=35ms  p(95)=98ms
  { service:case }............: avg=61ms  p(95)=180ms
kyc_request_duration...........: avg=18ms  p(95)=52ms
txn_request_duration...........: avg=35ms  p(95)=98ms
case_request_duration..........: avg=61ms  p(95)=180ms
aml_alerts_total...............: 312       (expected ~20 % of transactions)
aml_cases_opened...............: 298
aml_error_rate.................: 0.00%  ✓ rate<0.01
```

For RQ3, compare the `p(95)` column across the four `rq3_rate_*.json` files.
The pod CPU numbers come from Prometheus — query during each run:

```promql
rate(container_cpu_usage_seconds_total{namespace="aml"}[1m])
```

---

## 15. AIOps Pipeline in Action

### Check ml-engine health

```bash
curl -s http://localhost:8000/v1/health | jq .
# {"status":"ok","model":"IsolationForest","trained":true}
```

### Trigger a synthetic anomaly (high CPU)

```bash
# Simulate a CPU spike on transaction-monitoring
kubectl exec -n aml \
  $(kubectl get pod -n aml -l app.kubernetes.io/name=transaction-monitoring -o jsonpath='{.items[0].metadata.name}') \
  -- sh -c "dd if=/dev/zero of=/dev/null &"
```

### Watch the pipeline propagate

**1. Telemetry collector scrapes the spike** (every 30 s):

```bash
kubectl logs -n aiops deployment/telemetry-collector -f | grep "transaction-monitoring"
# Published metric: process_cpu_usage{service="transaction-monitoring"} = 0.94
```

**2. Stream processor normalises and pushes features:**

```bash
kubectl logs -n aiops deployment/stream-processor -f | grep "features"
# Published FeatureVector{service=transaction-monitoring, cpuUtilization=0.94, ...}
```

**3. ML engine scores the anomaly:**

```bash
curl -s http://localhost:8000/v1/incidents/score \
  -H 'Content-Type: application/json' \
  -d '[{"service":"transaction-monitoring","cpuUtilization":0.94,"p99LatencyMs":1200}]' | jq .
# [{"service":"transaction-monitoring","anomalyScore":0.93,"severity":"P1",
#   "rootCause":"CPU_SATURATION","probableBreachMs":45000}]
```

**4. Decision engine fires:**

```bash
kubectl logs -n aiops deployment/decision-engine -f
# Incident{service=transaction-monitoring, score=0.93, blastRadius=MEDIUM}
# → autoExecute=true, action=SCALE_OUT, targetReplicas=4
```

**5. Remediation engine acts:**

```bash
kubectl logs -n aiops deployment/remediation-engine -f
# Scaling transaction-monitoring from 2 → 4 replicas in namespace aml
kubectl get hpa -n aml transaction-monitoring
# MINPODS  MAXPODS  REPLICAS
# 2        15       4         ← scale-out confirmed
```

**6. Alerting service records the incident:**

```bash
curl -s http://localhost:9005/api/v1/incidents | jq '.[0]'
# {
#   "id": "inc-001",
#   "service": "transaction-monitoring",
#   "severity": "P1",
#   "score": 0.93,
#   "rootCause": "CPU_SATURATION",
#   "remediationStatus": "EXECUTED"
# }
```

**7. Feedback service labels the outcome:**

```bash
# After 5 min, mark as resolved
curl -s -X POST http://localhost:9006/api/v1/feedback \
  -H 'Content-Type: application/json' \
  -d '{"incidentId":"inc-001","outcome":"RESOLVED","effectiveMs":240000}' | jq .
```

---

## 15a. LLM Engine in Action

### Check LLM Engine health

```bash
curl -s http://localhost:8001/v1/health | jq .
# {"status":"UP","ollamaReady":true,"model":"qwen2.5:3b"}
```

### Check Ollama directly

```bash
# List downloaded models
curl -s http://localhost:11434/api/tags | jq '.models[].name'
# "qwen2.5:3b"

# Test Ollama directly (bypassing llm-engine)
curl -s http://localhost:11434/api/generate \
  -d '{"model":"qwen2.5:3b","prompt":"Hello","stream":false}' | jq .response
```

### Manually analyze an incident

```bash
curl -s -X POST http://localhost:8001/v1/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "incidentId": "inc-test-001",
    "service": "transaction-monitoring",
    "anomalyScore": 0.93,
    "rootCause": "CPU_SATURATION",
    "breachEtaMinutes": 12,
    "confidence": 0.88
  }' | jq .
```

Expected response (Qwen2.5:3b via Ollama):

```json
{
  "analysisId": "...",
  "incidentId": "inc-test-001",
  "model": "qwen2.5:3b",
  "explanation": "The transaction-monitoring service shows a critical anomaly score of 0.93, indicating severe CPU saturation. The AML rule evaluation pipeline is likely overwhelmed by a sudden surge in transaction volume...",
  "rootCause": "CPU_SATURATION",
  "recommendation": "Immediately scale out transaction-monitoring via HPA. Check for batch SWIFT/CRYPTO transactions causing rule-match storm.",
  "amlRisk": "HIGH",
  "confidence": 0.89,
  "cacheHit": false,
  "trainingSize": 0
}
```

### Add labeled training examples

> **Windows/PowerShell:** `curl` is an alias for `Invoke-WebRequest` — use `curl.exe` and
> write payloads to a temp file to avoid quote-stripping by the shell.

```powershell
# Seed 5 AML-specific labeled examples (runs entirely in PowerShell)
$examples = @(
  '{"incidentId":"inc-001","service":"transaction-monitoring","anomalyScore":0.92,"rootCause":"high-volume wire transfers to offshore accounts","outcome":"RESOLVED","actionTaken":"BLOCK_ACCOUNT","explanation":"Classic layering pattern; account blocked and SAR filed."}',
  '{"incidentId":"inc-002","service":"case-management","anomalyScore":0.87,"rootCause":"rapid sequential cash deposits below reporting threshold","outcome":"RESOLVED","actionTaken":"ESCALATE_CASE","explanation":"Structuring detected across 12 branches in 48h."}',
  '{"incidentId":"inc-003","service":"customer-kyc","anomalyScore":0.78,"rootCause":"KYC documents expired and transaction volume spiked 400%","outcome":"RESOLVED","actionTaken":"FREEZE_ACCOUNT","explanation":"Account frozen pending re-verification."}',
  '{"incidentId":"inc-004","service":"transaction-monitoring","anomalyScore":0.95,"rootCause":"round-trip fund transfers between related shell entities","outcome":"RESOLVED","actionTaken":"BLOCK_ACCOUNT","explanation":"Shell company network identified; all related accounts reported."}',
  '{"incidentId":"inc-005","service":"transaction-monitoring","anomalyScore":0.81,"rootCause":"high-risk jurisdiction transfers with no stated business purpose","outcome":"RESOLVED","actionTaken":"ESCALATE_CASE","explanation":"Transfers to FATF blacklisted country flagged and escalated."}'
)
$tmp = "$env:TEMP\train_ex.json"
foreach ($body in $examples) {
  [System.IO.File]::WriteAllText($tmp, $body, [System.Text.Encoding]::UTF8)
  curl.exe -s -X POST http://localhost:8001/v1/train -H "Content-Type: application/json" --data-binary "@$tmp"
  Write-Host ""
}
# Each line prints: {"status":"accepted","trainingExamples":<n>}
```

### Trigger LoRA fine-tuning

```powershell
# Start training (runs in background — non-blocking)
curl.exe -s -X POST http://localhost:8001/v1/train/start
# {"status":"started","examples":5,"model":"Qwen/Qwen2.5-0.5B-Instruct",
#  "message":"LoRA fine-tuning started. Check GET /v1/train/status for progress."}
```

### Monitor training progress

```powershell
# Check status once
curl.exe -s http://localhost:8001/v1/train/status

# Poll every 10 seconds until COMPLETED (PowerShell — no watch on Windows)
while ($true) {
  $s = curl.exe -s http://localhost:8001/v1/train/status | ConvertFrom-Json
  Write-Host "$(Get-Date -Format 'HH:mm:ss')  status=$($s.status)  adapterReady=$($s.adapterReady)  loss=$($s.trainLoss)"
  if ($s.status -eq 'COMPLETED' -or $s.status -eq 'FAILED') { break }
  Start-Sleep -Seconds 10
}

# Expected final output:
# {"status":"COMPLETED","trainLoss":0.148,"adapterReady":true,"lossHistory":[2.1,1.4,0.9,0.5,0.25,0.15]}
```

Training uses `Qwen/Qwen2.5-0.5B-Instruct` by default (fast CPU, ~2-5 min for
5 epochs on 5 examples). Change `TRAINING_MODEL=Qwen/Qwen2.5-3B-Instruct` in
the K8s Deployment env for the full model (GPU recommended).

Once `adapterReady: true`, the dashboard shows **● LoRA** next to analyses,
and `cacheHit: true` in API responses indicates the adapter was applied.

### Check training stats

```bash
curl -s http://localhost:8001/v1/stats | jq .
# {
#   "trainingExamples": 5,
#   "totalAnalyses": 12,
#   "modelId": "qwen2.5:3b",
#   "adapterReady": true,
#   "ollamaUrl": "http://ollama:11434"
# }
```

---

## 15b. AMLOps Agent — Chat Dashboard

The **agent-service** (port 9007) is an AI-powered chat interface that lets you
diagnose anomalies, run kubectl commands, query Prometheus/Loki, and request fixes
— all in natural language. It uses the **same Qwen2.5-3B model via Ollama** that
already runs in the cluster — no external API key required.

### Build and deploy

```powershell
# Via Makefile (recommended)
make image-aiops-agent
make push-aiops        # pushes agent-service along with other AIOps images
make k8s-agent         # applies RBAC + deployment + service

# Or manually
docker build -t localhost:5001/agent-service:0.1.0 aiops/agent-service/
docker push localhost:5001/agent-service:0.1.0
kubectl apply -f aiops/agent-service/k8s/rbac.yaml
kubectl apply -f aiops/agent-service/k8s/deployment.yaml
kubectl apply -f aiops/agent-service/k8s/service.yaml
```

> **Note:** The deployment uses `resources: {}` (BestEffort QoS) to allow scheduling on a
> memory-overcommitted Docker Desktop node. The service runs ~50 MB actual RAM.

### Access the chat UI

```powershell
# Via Makefile
make pf-agent

# Or manually
kubectl port-forward -n aiops svc/agent-service 9007:9007
```

Then open **http://localhost:9007** — a dark-themed chat dashboard opens.

### What you can ask

| Intent | Example prompt |
|---|---|
| Health overview | "Which services are degraded right now?" |
| Anomaly diagnosis | "Why is transaction-monitoring showing high latency?" |
| Root cause | "Show me the root cause of the latest incident" |
| Log investigation | "Show ERROR logs from case-management in the last 10 minutes" |
| Fix issue | "Restart the ml-engine pod" |
| Scale | "Scale transaction-monitoring to 2 replicas" |
| Coding | "Explain how the Pearson RCA algorithm works" |
| Architecture | "What Kafka topics does the decision-engine consume?" |

### Re-deploy after code change

```powershell
make image-aiops-agent
docker push localhost:5001/agent-service:0.1.0
kubectl rollout restart deployment/agent-service -n aiops
```

---

## 15c. Full-Stack MELT — All Services Monitored

Every service in both `aml` and `aiops` namespaces feeds all four signal types
into the anomaly-detection pipeline.

### Signal coverage map

| Signal | AML (3 services) | AIOps (6 Spring Boot + 2 Python) |
|--------|-----------------|----------------------------------|
| **M** Metrics | `PrometheusScraperAdapter` → `aiops.telemetry.metrics` | Same — all 11 services in SERVICES list, PromQL `{job=~"..."}` filter |
| **E** Events | `AmlEventConsumer` ← `aml.alerts.events`, `aml.cases.events` | `AiopsEventConsumer` ← `aiops.incidents`, `aiops.decisions`, `aiops.actions`, `aiops.outcomes`, `aiops.alerts`, `aiops.features`, `aml.llm.analysis`, **`aiops.service.heartbeat`** |
| **L** Logs | `LokiReader` — `{namespace=~"aml\|aiops"}` ERROR logs | Same query covers both namespaces |
| **T** Traces | `TempoReader` — polls Tempo `/api/search` per service | Same — all 11 services polled |

### Verify Prometheus is scraping AIOps services

```powershell
# Open Prometheus UI → Status → Targets
# All aiops ServiceMonitors should show state=UP
curl -s "http://localhost:9090/api/v1/targets" | `
  ConvertFrom-Json | Select-Object -ExpandProperty data | `
  Select-Object -ExpandProperty activeTargets | `
  Where-Object { $_.labels.namespace -eq "aiops" } | `
  Select-Object labels, health
```

Or query a metric directly:

```promql
# AIOps service HTTP request rate
rate(http_server_requests_seconds_count{namespace="aiops"}[5m])

# ml-engine / llm-engine FastAPI request rate
rate(http_requests_total{namespace="aiops"}[5m])

# Stream-processor features processed (from AiopsEventConsumer)
aiops_features_processed_total
```

### Verify telemetry-collector ingests all signals

```powershell
# Metrics — should see all 11 services
kubectl logs -n aiops deployment/telemetry-collector | Select-String "Published MetricSignal"

# Logs — should see both aml and aiops namespaces
kubectl logs -n aiops deployment/telemetry-collector | Select-String "Ingested.*log signals"

# Traces — should see services from both namespaces
kubectl logs -n aiops deployment/telemetry-collector | Select-String "Ingested.*trace signals"

# AIOps events — should see incidents, decisions, actions, alerts, features
kubectl logs -n aiops deployment/telemetry-collector | Select-String "Converted AIOps event"
```

### Verify LLM receives enriched MELT context

The llm-engine maintains rolling per-service buffers filled by 3 background
Kafka consumers (`aiops.telemetry.logs`, `aiops.telemetry.traces`, `aiops.features`).
When an incident arrives, the Ollama prompt is automatically enriched:

```powershell
kubectl logs -n aiops deployment/llm-engine | Select-String "LLM analyzed"
# Each analysis now includes recent ERROR logs, trace durations, and metric window
# values for the affected service — not just the bare anomaly score.
```

To inspect a recent analysis with full MELT context visible:

```powershell
curl.exe -s http://localhost:8001/v1/analyses | ConvertFrom-Json | Select-Object -First 1
```

### Event flow for AIOps services

```
alerting-service receives incident
  → AlertRoutingService.route() creates Alert
  → publishes to aiops.alerts                   ← new
      → AiopsEventConsumer converts to MetricSignal
          → aiops.telemetry.metrics → stream-processor
              → aiops.features → ml-engine
                  → can detect: alerting-service firing too many P1 alerts
```

---

## 16. Dashboard Tour & Testing

### Start the dashboard

```powershell
# Terminal 1 — keep open
kubectl port-forward -n aiops svc/aiops-dashboard 3001:80
```

Open `http://localhost:3001` then **`Ctrl+Shift+R`** (hard-refresh clears the
1-year JS cache that nginx sets).

> **Note:** The dashboard's nginx proxies `/api/v1/` → alerting-service and
> `/api/llm/` → llm-engine inside the cluster. No extra port-forwards needed.

---

### Navbar pages

| Page | Contents |
|---|---|
| **Overview** | 11-service health grid (AML + AIOps sections) · Live anomaly chart · Stats row · Mini incident table · Remediation log · Outcome chart |
| **Incidents** | Full table · Severity filter (ALL / P1–P4) · Service dropdown · Green live-dot for SSE events |
| **Remediations** | Status filter tabs · Pre/post state · `targetNamespace` column shows correct `aml`/`aiops` namespace · Success rate stats |
| **LLM Analysis** | Training status panel · Loss sparkline · Full analysis cards with risk badges |
| **Outcomes** | Outcome chart · SLO delta table · Average SLO gain metric |
| **MELT** | **All/AML/AIOps namespace toggle** — filters all four panels simultaneously · Metrics: PromQL `namespace=~` filter · Logs: Loki `{namespace=~"aml\|aiops"}` · Events: namespace badge per event · Traces: namespace badge + slow-trace (>500ms) highlight |

The navbar always shows a live **● CRITICAL / WARNING / All OK** badge from the
health endpoint, and a real-time clock that ticks every second.

---

### Verify each page loads

Check that every API call returns 200 — run this smoke-test from any terminal:

```powershell
@(
  "http://localhost:3001/api/v1/incidents",
  "http://localhost:3001/api/v1/remediations",
  "http://localhost:3001/api/v1/services/health",
  "http://localhost:3001/api/v1/outcomes/summary",
  "http://localhost:3001/api/llm/v1/stats"
) | ForEach-Object {
  $code = curl.exe -s -o NUL -w "%{http_code}" $_
  Write-Host "$code  $_"
}
# All should return 200. 502 = backing pod unreachable from dashboard nginx.
```

---

### Generate live data (trigger SSE updates)

The `telemetry-collector` is **Kafka-only** — it has no HTTP endpoint for
incidents. Inject test incidents directly into the `aiops.incidents` Kafka
topic.  Spring's `JsonDeserializer` requires a `__TypeId__` header; the
`kubectl cp` approach below embeds a real tab as the delimiter without any
shell-escaping surprises.

```powershell
# 1. Write the message to a local file — UTF-8 no BOM, real tab between header and body
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$tab = "`t"
$ts  = [DateTime]::UtcNow.ToString('o')
$json = '{"incidentId":"test-001","detectedAt":"' + $ts +
        '","anomalyScore":0.93,"affectedServices":["transaction-monitoring"],' +
        '"rootCauseRanking":[{"component":"db-pool","weight":0.8}],' +
        '"breachEtaMinutes":12,"confidence":0.91}'
$msg = "__TypeId__:com.alexbank.aiops.alerting.domain.Incident${tab}${json}"
[System.IO.File]::WriteAllText("$PWD\kafka_msg.txt", "$msg`n", $utf8NoBom)

# 2. Copy to the Kafka pod.
#    Use a relative path — kubectl cp misreads "C:\..." as pod-name:path.
kubectl cp .\kafka_msg.txt kafka-0:/tmp/kafka_msg.txt -n data

# 3. Produce — default headers.delimiter is tab, matching the file format.
kubectl exec -n data kafka-0 -- bash -c "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic aiops.incidents --property parse.headers=true < /tmp/kafka_msg.txt"
```

The **Overview** chart and the navbar **CRITICAL** badge should update within
a few seconds via SSE. The **Incidents** page will show a green dot next to
the live event.

To inject a burst of incidents (stress-test the live chart):

```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$tab = "`t"
1..5 | ForEach-Object {
  $ts   = [DateTime]::UtcNow.ToString('o')
  $score = [Math]::Round(0.80 + $_ * 0.03, 2)
  $eta   = 10 + $_
  $json = '{"incidentId":"test-00' + $_ + '","detectedAt":"' + $ts +
          '","anomalyScore":' + $score +
          ',"affectedServices":["payment-service"],' +
          '"rootCauseRanking":[{"component":"db-pool","weight":0.8}],' +
          '"breachEtaMinutes":' + $eta + ',"confidence":0.88}'
  $msg = "__TypeId__:com.alexbank.aiops.alerting.domain.Incident${tab}${json}"
  [System.IO.File]::WriteAllText("$PWD\kafka_msg.txt", "$msg`n", $utf8NoBom)
  kubectl cp .\kafka_msg.txt kafka-0:/tmp/kafka_msg.txt -n data
  kubectl exec -n data kafka-0 -- bash -c "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic aiops.incidents --property parse.headers=true < /tmp/kafka_msg.txt"
  Start-Sleep -Milliseconds 600
}
```

---

### Verify SSE stream directly

```powershell
# Must run in a dedicated terminal — streams until Ctrl+C
curl.exe -N -H "Accept: text/event-stream" http://localhost:9005/api/v1/stream
```

The green **● SSE live · N events** counter on the Overview page confirms the
browser's EventSource connection is active.

---

### Page-by-page reference

**Overview**
- Health cards: all **11 services** (3 AML + 8 AIOps) grouped into labelled sections
  - **AML Services** — 3-col grid, full-size cards (`customer-kyc`, `transaction-monitoring`, `case-management`)
  - **AIOps Services** — 4-col compact grid, all 8 AIOps services
  - Each card shows a namespace badge (blue `aml` / purple `aiops`)
  - Score = `max(incident_anomaly_score, prometheus_cpu_score)` — idle services show `≥ 0.05` (alive floor)
  - green OK (score < 0.5) / orange WARNING (≥ 0.5) / red CRITICAL (≥ 0.75)
- Live anomaly chart: 30-second buckets, last 20 windows (≈10 min), P1/P2 reference lines
- Stats row: total incidents · P1 active · remediations · resolved %

**Incidents** — Severity filter + service dropdown. Live SSE events show a green
dot. Score → P1 ≥ 0.90 / P2 ≥ 0.75 / P3 ≥ 0.50 / P4 below.

**Remediations** — Status tabs (ALL / COMPLETED / EXECUTING / PENDING / FAILED /
VETOED). Pre/post state columns show replica counts or config diffs.

**LLM Analysis**
- Training panel: status badge · example count · loss sparkline
- Analysis cards: AML risk badge · explanation · root cause · recommendation
- **● LoRA** green label = fine-tuned adapter was used for that inference

**Outcomes** — SLO before/after columns. Positive SLO delta = remediation worked.
Outcomes feed back into the LoRA training buffer automatically via feedback-service.

---

## 17. Observability

### Grafana

Open `http://localhost:3000`.

| Field | Value |
|---|---|
| Username | `admin` |
| Password | `eJxxkpooypo7yjecjesmTGn6dSwSh7gEBS9Rz7bn` |

> Retrieve at any time (PowerShell):
> ```powershell
> $pw = kubectl get secret -n monitoring kube-prom-stack-grafana -o jsonpath="{.data.admin-password}"
> [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String($pw))
> ```

Pre-built dashboards:

- **Kubernetes / Compute Resources / Namespace** — CPU + memory per namespace
- **Kubernetes / Workloads** — per-deployment HPA replica trends
- **JVM Overview** (if Spring Boot actuator Micrometer is scraped) — heap, GC, threads

### MELT Coverage

Both namespaces (`aml` and `aiops`) are fully instrumented with the same MELT treatment:

| Signal | AML services | AIOps services |
|--------|-------------|----------------|
| **Metrics** | `/actuator/prometheus` scraped via ServiceMonitor | Same — `/actuator/prometheus` (Java) or `/metrics` (Python ml-engine, llm-engine) |
| **Events** | Kafka topics `aml.*` | Kafka topics `aiops.*` |
| **Logs** | Loki `{namespace="aml"}` — ERROR level | Loki `{namespace=~"aml\|aiops"}` — same query covers both |
| **Traces** | `OTEL_EXPORTER_OTLP_ENDPOINT` → Tempo | Same env var on all AIOps Java services |

All 8 AIOps services have `ServiceMonitor` CRDs in `infrastructure/k8s/aiops.yml` with `release: kube-prom-stack` label so the Prometheus Operator picks them up automatically.

### Prometheus

`http://localhost:9090` — query examples:

```promql
# AML service CPU utilisation
rate(container_cpu_usage_seconds_total{namespace="aml"}[5m])

# transaction-monitoring p99 latency
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{
    namespace="aml",
    app_kubernetes_io_name="transaction-monitoring"
  }[5m])
)

# AIOps pipeline HTTP request rate (all Java services)
rate(http_server_requests_seconds_count{namespace="aiops"}[5m])

# AIOps service JVM heap
jvm_memory_used_bytes{namespace="aiops", area="heap"}

# ml-engine / llm-engine request throughput (Python FastAPI)
rate(http_requests_total{namespace="aiops",app_kubernetes_io_name=~"ml-engine|llm-engine"}[5m])
```

### Distributed Traces (Tempo)

In Grafana → Explore → Select **Tempo** datasource → search by service name.

AML:  `service.name = transaction-monitoring`
AIOps: `service.name = decision-engine` (or any other AIOps service)

Traces show the full call chain:
`transaction-monitoring → customer-kyc → Kafka producer`.

### Logs (Loki)

In Grafana → Explore → Select **Loki** datasource:

```logql
{namespace="aml", app_kubernetes_io_name="transaction-monitoring"}
  |= "FLAGGED"
```

```logql
{namespace=~"aml|aiops"}
  | json | level = "ERROR"
```

```logql
{namespace="aiops"}
  |= "autoExecute=true"
```

---

## 18. One-Shot Full Deploy

```bash
make cluster-up
make k8s-full
```

`k8s-full` expands to:

```
k8s-data  →  k8s-monitoring
  → image-aml  push-aml  k8s-aml
  → image-aiops  push-aiops  k8s-aiops
  → image-dashboard  push-dashboard  k8s-dashboard
  → k8s-topics
```

Total wall-clock time on a 16-core laptop: ~12 minutes.

---

## 19. Teardown

### Delete application namespaces only

```bash
make k8s-delete
# Deletes: aml, aiops, data namespaces
```

### Remove observability stack and registry

```bash
make cluster-down
# Uninstalls Helm releases in the monitoring namespace, stops the local registry
```

### Stop local Docker-compose infra

```bash
make infra-down          # stops AML infra
make aiops-infra-down    # stops AIOps supplementary containers
```

---

## 20. Troubleshooting

### AML service returns `invalid-request` — "parameter name not available via reflection"

Spring Framework 6.1+ requires the Java `-parameters` compiler flag to resolve
`@PathVariable` / `@RequestParam` names from bytecode. Without it every path
variable endpoint fails before the handler runs.

**Fix** — the root `pom.xml` must include:

```xml
<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs><arg>-parameters</arg></compilerArgs>
    </configuration>
</plugin>
```

After adding it, rebuild and redeploy:

```bash
make image-aml push-aml
kubectl rollout restart deployment/customer-kyc deployment/transaction-monitoring deployment/case-management -n aml
kubectl rollout status deployment/customer-kyc -n aml --timeout=120s
```

> **Important:** changing `pom.xml` alone is not enough — the running pod keeps the
> old compiled JAR until the image is rebuilt and the deployment is restarted.
> Docker invalidates its cache from the `COPY pom.xml` layer, so `make image-aml`
> will recompile with `-parameters` applied.

### Pod stuck in `ImagePullBackOff`

```bash
kubectl describe pod <pod-name> -n aml | grep -A5 "Events:"
```

The image was not pushed to the local registry. Re-run:

```bash
make push-aml   # or push-aiops / push-dashboard
```

### Kafka consumer lag growing

```bash
kubectl exec -n data kafka-0 \
  -- /opt/kafka/bin/kafka-consumer-groups.sh \
     --bootstrap-server localhost:9092 \
     --describe --all-groups
```

If `aiops-stream-processor` group has high lag, check stream-processor logs:

```bash
kubectl logs -n aiops deployment/stream-processor --tail=50
```

### AML service not reaching Postgres

```bash
kubectl exec -n aml \
  $(kubectl get pod -n aml -l app.kubernetes.io/name=customer-kyc -o jsonpath='{.items[0].metadata.name}') \
  -- sh -c "nc -zv postgres-kyc-postgresql.data.svc.cluster.local 5432"
```

If this fails, first check service endpoints — the Helm-managed services can end up with merged selectors that match nothing:

```bash
kubectl get endpoints -n data
```

If any of `postgres-cases-postgresql`, `postgres-kyc-postgresql`, or `postgres-monitoring-postgresql` shows `<none>`, patch the selector:

```bash
kubectl patch svc postgres-cases-postgresql -n data --type=json \-p '[{"op":"replace","path":"/spec/selector","value":{"app":"postgres-cases"}}]'
kubectl patch svc postgres-kyc-postgresql -n data --type=json \ -p '[{"op":"replace","path":"/spec/selector","value":{"app":"postgres-kyc"}}]'
kubectl patch svc postgres-monitoring-postgresql -n data --type=json \ -p '[{"op":"replace","path":"/spec/selector","value":{"app":"postgres-monitoring"}}]'
```

Then restart the stuck deployments:

```bash
kubectl rollout restart deployment/case-management deployment/customer-kyc deployment/transaction-monitoring -n aml
```

Also check the `data` namespace pods are running:

```bash
kubectl get pods -n data
```

### ml-engine returns 503

The Python model may not have been trained yet. Force a training run:

```bash
curl -s -X POST http://localhost:8000/v1/models/update \
  -H 'Content-Type: application/json' \
  -d '{"trigger":"manual"}' | jq .
```

### Dashboard shows "No data"

The dashboard calls `alerting-service` at `/api/v1/*`. Check CORS and
connectivity:

```bash
curl -s http://localhost:9005/api/v1/services/health | jq .
```

If port-forward is not running, restart it:

```bash
make pf-aiops
```

### AIOps dashboard shows score 0.000 / "Waiting for anomaly data"

Five bugs in the scoring pipeline, all requiring fixes and a redeploy:

**1. Missing ServiceMonitors** — Prometheus never scraped the AML services.

```bash
kubectl apply -f infrastructure/k8s/aml-platform.yml   # now includes ServiceMonitor resources
```

Verify after ~60 s:
```bash
kubectl exec -n aiops deployment/telemetry-collector -- \
  sh -c "wget -qO- 'http://prometheus-operated.monitoring.svc.cluster.local:9090/api/v1/query?query=http_server_requests_seconds_count' | grep -o '\"job\":\"[^\"]*\"' | sort -u"
# should print customer-kyc, transaction-monitoring, case-management
```

**2. Wrong metric name in scraper** — `PrometheusScraperAdapter.java` queried
`aml_outbox_pending_events` but the actual metric is `aml_outbox_dispatched_total`.
Fixed in source.

**3. Kafka consumer deserializer mismatch** — `telemetry-collector` used `JsonDeserializer`
but its `AmlEventConsumer` listener expected `String`. Changed to `StringDeserializer` in
`application.yml`.

**4. RocksDB missing `libstdc++`** — `stream-processor` Dockerfile used Alpine (`eclipse-temurin:21-jre-alpine`)
which ships without `libstdc++.so.6`. Added `apk add --no-cache libstdc++` to the runtime
stage of `aiops/stream-processor/Dockerfile`.

**5. `MetricAgg` not serializable** — Jackson couldn't serialize `MetricAgg` (package-private
fields, no getters). Fixed by adding
`mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)` in
`FeatureExtractionTopology.java`.

**6. `aiops.features` topic not auto-created** — the broker's auto-create was off.
Create it manually (one-time):
```bash
kubectl exec -n data kafka-0 -- bash -c \
  "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
   --create --topic aiops.features --partitions 3 --replication-factor 1"
```

**7. Spring Kafka `JsonDeserializer` missing `use.type.headers: false`** — all Spring Kafka
consumers fail when messages from Python (confluent-kafka) arrive without Spring type headers.
Added `spring.json.use.type.headers: false` to all five consumers' `application.yml`.
alerting-service also needed per-`@KafkaListener` `spring.json.value.default.type` since it
handles three different types (`Incident`, `RemediationRecord`, `IncidentOutcome`).

After all fixes, rebuild and redeploy:
```bash
make image-aiops push-aiops
kubectl rollout restart deployment/telemetry-collector deployment/stream-processor \
  deployment/alerting-service deployment/decision-engine \
  deployment/remediation-engine deployment/feedback-service -n aiops
```

### Grafana pod in `CrashLoopBackOff` — "Only one datasource per organization can be marked as default"

The `loki-stack` Helm chart auto-creates a `loki-loki-stack` ConfigMap in the `monitoring`
namespace with `isDefault: true` for Loki. This conflicts with the Prometheus datasource
(also `isDefault: true`) from the kube-prometheus-stack chart.

**Fix** — delete the duplicate ConfigMap (Loki is already registered via `grafana-extra-datasources`):

```bash
kubectl delete configmap loki-loki-stack -n monitoring
kubectl rollout restart deployment/kube-prom-stack-grafana -n monitoring
kubectl rollout status deployment/kube-prom-stack-grafana -n monitoring --timeout=120s
```

### RESTART_POD / SCALE_OUT fails with 404 for AIOps services

The remediation-engine previously used a single `defaultNamespace=aml` for all
K8s operations, so actions targeting AIOps services (e.g. `alerting-service`,
`decision-engine`) sent requests to the wrong namespace and got a 404.

**Fix** — `RemediationService` now resolves the namespace per service:

```java
// AIOPS_SERVICES set → "aiops" namespace; everything else → "aml" namespace
private String resolveNamespace(String service) {
    return AIOPS_SERVICES.contains(service) ? aiopsNamespace : amlNamespace;
}
```

Two env vars control the target namespaces (already set in `aiops.yml`):

```yaml
- name: AML_NAMESPACE
  value: aml
- name: AIOPS_NAMESPACE
  value: aiops
```

Rebuild and redeploy after any pom/yaml change:

```powershell
make image-aiops-remediation
kubectl rollout restart deployment/remediation-engine -n aiops
```

### Service health shows score 0.000 for idle AIOps services

`/api/v1/services/health` previously derived scores only from `IncidentStore`.
Services with no recent anomaly incidents (e.g. `decision-engine`,
`stream-processor`, `telemetry-collector`) always showed `0.000`.

**Fix (two-layer approach):**

**Layer 1 — `PrometheusHealthFetcher`** supplements incident scores with live
Prometheus data (filtered by `job=~"<all-11-services>"`, not namespace label):

| Query | Services covered | Effect |
|-------|-----------------|--------|
| `up{job=~"..."}` | All scraped services | Floor `0.05` for any running service |
| `process_cpu_usage{job=~"..."}` | Java / Spring Boot | Real CPU fraction (0–1) |
| `rate(process_cpu_seconds_total{job=~"..."}[2m])` | Python (ml-engine, llm-engine) | Normalised CPU fraction |

Final score = `max(incident_score, cpu_score)`.

**Layer 2 — `aiops.service.heartbeat` Kafka topic** — each AIOps Java service
publishes its own health signal every 30 s, independent of pipeline activity:

| Service | HeartbeatPublisher location |
|---------|----------------------------|
| `decision-engine` | `infrastructure/kafka/HeartbeatPublisher.java` |
| `remediation-engine` | `infrastructure/kafka/HeartbeatPublisher.java` |
| `stream-processor` | `infrastructure/kafka/HeartbeatPublisher.java` |
| `telemetry-collector` | `infrastructure/kafka/HeartbeatPublisher.java` |

Payload: `{ service, timestamp, status="UP", eventsProcessed }`.
`AiopsEventConsumer` picks up `aiops.service.heartbeat` and converts it to a
`MetricSignal(service, "aiops_service_heartbeat_total", eventsProcessed)`.
`@EnableScheduling` was added to DecisionEngine, RemediationEngine, StreamProcessor
Application classes to activate the `@Scheduled` annotation.

If services still show `0.000` after rebuilding, verify:
1. ServiceMonitors are applied and targets show **state=UP** in Prometheus
2. `aiops.service.heartbeat` topic exists (`make k8s-topics` creates it)

```powershell
# Check Prometheus targets
curl -s "http://localhost:9090/api/v1/targets" |
  ConvertFrom-Json | Select-Object -ExpandProperty data |
  Select-Object -ExpandProperty activeTargets |
  Where-Object { $_.labels.job -in @("decision-engine","stream-processor","telemetry-collector","remediation-engine") } |
  Select-Object @{n="job";e={$_.labels.job}}, health

# Check heartbeat messages flowing
kubectl exec -n data kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic aiops.service.heartbeat --from-beginning --max-messages 10
```

Rebuild all changed services after the fix:

```powershell
make image-aiops-collector image-aiops-stream image-aiops-decision image-aiops-remediation
kubectl rollout restart deployment/telemetry-collector deployment/stream-processor \
  deployment/decision-engine deployment/remediation-engine -n aiops
```

### HPA not scaling

HPA requires the `metrics-server` to be installed:

```bash
kubectl top nodes   # should return values, not "error"
```

The `kube-prometheus-stack` installs the metrics-server adapter. If not:

```bash
helm upgrade --install metrics-server metrics-server/metrics-server \
  -n kube-system \
  --set args="{--kubelet-insecure-tls}"
```

---

## Quick Reference Card

```bash
# ── First-time setup ───────────────────────────────────────
make cluster-up               # start local registry + observability stack
make k8s-full                 # build + push + deploy everything
# Wait for Ollama to pull qwen2.5:3b (~2 GB, first start only)
kubectl get pod -n aiops -l app.kubernetes.io/name=ollama -w

# ── Auto port-forwards on login (run once as Administrator) ─
# Registers a Task Scheduler task that re-starts all port-forwards
# 60 s after logon — survives machine reboots automatically.
powershell -ExecutionPolicy Bypass -File scripts\register-task.ps1

# To trigger immediately without rebooting:
Start-ScheduledTask -TaskName "AML-Platform-PortForwards"

# Log: scripts\port-forward.log
#
# Note: crashed-pod cleanup is handled automatically by the remediation engine
# (KubernetesActuator.cleanupCrashedPods) after every RESTART_POD action.

# ── Day-to-day ─────────────────────────────────────────────
make k8s-status               # pod / svc / hpa overview
make pf-aml                   # forward :8080 :8081 :8082
make pf-aiops                 # forward :9001-9006 :8000 :8001 :9007
make pf-dashboard             # forward :3001  (dashboard)
make pf-obs                   # forward :3000 :9090  (Grafana/Prom)

# ── k6 load testing ────────────────────────────────────────
# Requires: make pf-aml  (port-forwards :8080 :8081 :8082)
k6 run k6/load-test.js                     # 50-VU baseline (paper §4.1)
k6 run --out json=results/baseline.json \
       k6/load-test.js                     # save full JSON for analysis

# RQ3: all four sampling-rate runs (patches ConfigMap + restarts pods)
cd k6 ; .\run-rq3.ps1 ; cd ..

# Single RQ3 rate manually
kubectl patch configmap aiops-config -n aiops --type merge `
  -p '{"data":{"TRACE_SAMPLE_RATE":"0.1"}}'
kubectl rollout restart deployment -n aml
k6 run -e SAMPLE_RATE=0.1 `
       --out json=k6/results/rq3_0_1.json `
       k6/rq3-overhead.js

# ── Postman API testing ─────────────────────────────────────
# Import postman/AML-Platform.postman_collection.json
#      + postman/AML-Platform.postman_environment.json
# Select env "AML Platform – Local", then run any folder.
# End-to-End Flow folder runs the full lifecycle in order.
make image-aml push-aml
kubectl rollout restart deployment/customer-kyc deployment/transaction-monitoring deployment/case-management -n aml
kubectl rollout status deployment/customer-kyc -n aml --timeout=120s

# ── LLM engine (Ollama + Qwen2.5) ──────────────────────────
curl.exe http://localhost:11434/api/tags       # list Ollama models
curl.exe http://localhost:8001/v1/health       # llm-engine liveness
curl.exe http://localhost:8001/v1/stats        # training stats
curl.exe http://localhost:8001/v1/train/status # LoRA training status + loss
curl.exe http://localhost:8001/v1/analyses     # recent Qwen2.5 analyses
curl.exe -X POST http://localhost:8001/v1/train/start  # kick off LoRA training
# SSE stream (dashboard uses this)
curl.exe -N http://localhost:9005/api/v1/stream

# ── Re-deploy after code change ────────────────────────────
make image-kyc push-aml       # rebuild + push one image
kubectl rollout restart deployment/customer-kyc -n aml

# ── Re-deploy all AIOps after code change ──────────────────
make image-aiops push-aiops
kubectl rollout restart deployment -n aiops

# ── Agent chat dashboard ─────────────────────────────────────
make image-aiops-agent
docker push localhost:5001/agent-service:0.1.0
make k8s-agent
make pf-agent
# Open http://localhost:9007

# ── Re-deploy React dashboard after code change ─────────────
# Build context must be dashboard/ (not project root)
docker build -f dashboard/Dockerfile -t localhost:5001/aiops-dashboard:0.1.0 dashboard/
docker push localhost:5001/aiops-dashboard:0.1.0
kubectl rollout restart deployment/aiops-dashboard -n aiops
kubectl rollout status deployment/aiops-dashboard -n aiops --timeout=60s
# Restart port-forward (it dies when the pod restarts):
kubectl port-forward -n aiops svc/aiops-dashboard 3001:80
# Then hard-refresh the browser: Ctrl+Shift+R
# Notes:
#   - imagePullPolicy: Always  → pod always pulls latest image from registry
#   - nginx caches JS by content-hash for 1 year → Ctrl+Shift+R clears browser cache

# ── Teardown ────────────────────────────────────────────────
make cluster-down                 # remove observability stack + registry
```
