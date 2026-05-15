# Fix 01 — Migrate from k3d to Docker Desktop Kubernetes

## Problem
k3d was not available in the Git Bash PATH on Windows. `make cluster-up` silently failed because
`set -euo pipefail` caused the bash script to exit with no output when `k3d` was not found.

## Root Cause
k3d was installed but only accessible in certain shell environments. Helm was also only available
in the PowerShell PATH, not in Git Bash.

## Fix

### Replaced `infrastructure/scripts/bootstrap.sh` → `bootstrap.ps1`
```powershell
$ErrorActionPreference = 'Stop'
kubectl config use-context docker-desktop
$running = docker ps --format '{{.Names}}' | Where-Object { $_ -eq 'aml-registry' }
if ($running) { Write-Host "Registry already running." }
else { docker run -d --restart=always --name aml-registry -p 5001:5000 registry:2 }
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
kubectl create namespace monitoring 2>$null; $true
helm upgrade --install kube-prom-stack prometheus-community/kube-prometheus-stack -n monitoring --wait
helm upgrade --install tempo grafana/tempo -n monitoring --wait
helm upgrade --install loki grafana/loki-stack -n monitoring --wait
```

### Replaced `infrastructure/scripts/teardown.sh` → `teardown.ps1`
Uninstalls Helm releases and stops the `aml-registry` Docker container.

### Updated `Makefile`
```makefile
cluster-up:
    powershell -ExecutionPolicy Bypass -File infrastructure/scripts/bootstrap.ps1

cluster-down:
    powershell -ExecutionPolicy Bypass -File infrastructure/scripts/teardown.ps1
```

### Fixed `make k8s-status` echo garbling (Windows cmd.exe)
`@echo "\n──..."` prints Unicode box-drawing chars as garbage on Windows. Changed to:
```makefile
@echo.
@echo --- Section ---
```

### Fixed `pf-obs` service names
After kube-prometheus-stack install, correct service names are:
```makefile
kubectl port-forward -n monitoring svc/kube-prom-stack-kube-prome-prometheus 9090:9090
kubectl port-forward -n monitoring svc/kube-prom-stack-grafana               3000:80
```

## Files Changed
- `infrastructure/scripts/bootstrap.ps1` (new)
- `infrastructure/scripts/teardown.ps1` (new)
- `Makefile` — cluster-up/down targets, k8s-status, pf-obs
