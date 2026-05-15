# Docker Image Build Guide

All commands run from the **project root** (`aml-platform/`).  
The local registry must be running first — if not:

```powershell
docker run -d --restart=always --name aml-registry -p 5001:5000 registry:2
```

---

## Prerequisites

```powershell
# Verify registry is up
docker ps --filter "name=aml-registry"
# Should show: 0.0.0.0:5001->5000/tcp
```

---

## Build Context Quick Reference

| Group | `-f` path | Context arg |
|---|---|---|
| AML Java | `services/<name>/Dockerfile` | `.` (project root) |
| AIOps Python | `aiops/<name>/Dockerfile` | `aiops/` |
| ML engines | `ml/<name>/Dockerfile` | `ml/<name>/` |
| Dashboard | `dashboard/Dockerfile` | `dashboard/` |

> **Common mistake on Windows:** omitting the context arg causes Docker to snapshot `.` (the project root), which hits `$RECYCLE.BIN` and fails with *Access is denied*.

---

## AML Services (Java — build context = project root)

Root context is required so Maven can resolve sibling `pom.xml` files.

| Service | Dockerfile | Image |
|---|---|---|
| case-management | `services/case-management/Dockerfile` | `localhost:5001/case-management:0.1.0` |
| transaction-monitoring | `services/transaction-monitoring/Dockerfile` | `localhost:5001/transaction-monitoring:0.1.0` |
| customer-kyc | `services/customer-kyc/Dockerfile` | `localhost:5001/customer-kyc:0.3.0` |

```powershell
docker build -f services/case-management/Dockerfile       -t localhost:5001/case-management:0.1.0       .
docker build -f services/transaction-monitoring/Dockerfile -t localhost:5001/transaction-monitoring:0.1.0 .
docker build -f services/customer-kyc/Dockerfile          -t localhost:5001/customer-kyc:0.3.0           .

# Or all at once
make image-aml
```

---

## AIOps Services (Python — build context = `aiops/`)

| Service | Dockerfile | Image |
|---|---|---|
| telemetry-collector | `aiops/telemetry-collector/Dockerfile` | `localhost:5001/telemetry-collector:0.1.0` |
| stream-processor | `aiops/stream-processor/Dockerfile` | `localhost:5001/stream-processor:0.1.0` |
| decision-engine | `aiops/decision-engine/Dockerfile` | `localhost:5001/decision-engine:0.1.0` |
| remediation-engine | `aiops/remediation-engine/Dockerfile` | `localhost:5001/remediation-engine:0.1.0` |
| alerting-service | `aiops/alerting-service/Dockerfile` | `localhost:5001/alerting-service:0.1.0` |
| feedback-service | `aiops/feedback-service/Dockerfile` | `localhost:5001/feedback-service:0.1.0` |

```powershell
docker build -f aiops/telemetry-collector/Dockerfile -t localhost:5001/telemetry-collector:0.1.0 aiops/
docker build -f aiops/stream-processor/Dockerfile    -t localhost:5001/stream-processor:0.1.0    aiops/
docker build -f aiops/decision-engine/Dockerfile     -t localhost:5001/decision-engine:0.1.0     aiops/
docker build -f aiops/remediation-engine/Dockerfile  -t localhost:5001/remediation-engine:0.1.0  aiops/
docker build -f aiops/alerting-service/Dockerfile    -t localhost:5001/alerting-service:0.1.0    aiops/
docker build -f aiops/feedback-service/Dockerfile    -t localhost:5001/feedback-service:0.1.0    aiops/

# Or all at once (includes ML services below)
make image-aiops
```

---

## ML Services (Python — build context = service subdirectory)

Each ML service uses its own subdirectory as context — not `aiops/` and not the root.

| Service | Dockerfile | Image |
|---|---|---|
| ml-engine | `ml/ml-engine/Dockerfile` | `localhost:5001/ml-engine:0.1.0` |
| llm-engine | `ml/llm-engine/Dockerfile` | `localhost:5001/llm-engine:0.1.0` |

```powershell
docker build -f ml/ml-engine/Dockerfile  -t localhost:5001/ml-engine:0.1.0  ml/ml-engine/
docker build -f ml/llm-engine/Dockerfile -t localhost:5001/llm-engine:0.1.0 ml/llm-engine/
```

> **Known fix:** `ml/llm-engine/requirements.txt` pins `transformers==4.46.3`
> (bumped from 4.45.2 — `trl==0.12.0` requires `>=4.46.0`).

---

## React Dashboard (Node/Nginx — build context = `dashboard/`)

```powershell
docker build -f dashboard/Dockerfile -t localhost:5001/aiops-dashboard:0.1.0 dashboard/
```

> **Known fix:** `dashboard/tsconfig.json` targets `ES2022`
> (bumped from ES2020 — `Array.at()` requires ES2022).

---

## Build Everything + Push

```powershell
# Build all groups
make image-aml        # case-management, transaction-monitoring, customer-kyc
make image-aiops      # telemetry-collector, stream-processor, ml-engine,
                      # llm-engine, decision-engine, remediation-engine,
                      # alerting-service, feedback-service
make image-dashboard  # aiops-dashboard

# Push all to local registry
make push-aml
make push-aiops
make push-dashboard
```
