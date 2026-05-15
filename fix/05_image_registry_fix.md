# Fix 05 — Image Registry URL Fix (k3d → localhost:5001)

## Problem
AML and AIOps pods showed `ImagePullBackOff` because K8s manifests referenced
`aml-registry:5000/...` — the in-cluster DNS name used by k3d's local registry.
Docker Desktop Kubernetes cannot resolve that hostname.

## Root Cause
Manifests were written for a k3d cluster with a local registry mounted as `aml-registry:5000`
inside the cluster. Docker Desktop uses a standalone Docker container for the registry,
accessible as `localhost:5001` from both the host and the cluster (since Docker Desktop
Kubernetes shares the host Docker daemon).

## Fix

### Updated all image references in K8s manifests
Changed from: `aml-registry:5000/<service>:<version>`
Changed to: `localhost:5001/<service>:<version>`

Applied to:
- `infrastructure/k8s/aml-platform.yml` — all 3 AML services
- `infrastructure/k8s/aiops.yml` — all 7 AIOps services
- `infrastructure/k8s/dashboard.yml` — aiops-dashboard

### Local Registry Setup
The registry runs as a Docker container started by `bootstrap.ps1`:
```powershell
docker run -d --restart=always --name aml-registry -p 5001:5000 registry:2
```

On Docker Desktop, the cluster shares the host's Docker daemon, so pushing to
`localhost:5001` (host) makes the image immediately available to pods pulling from
`localhost:5001` (in-cluster, routed to host loopback → registry container).

## Files Changed
- `infrastructure/k8s/aml-platform.yml`
- `infrastructure/k8s/aiops.yml`
- `infrastructure/k8s/dashboard.yml`
- `infrastructure/scripts/bootstrap.ps1`
- `Makefile` — REGISTRY variable set to `localhost:5001`
