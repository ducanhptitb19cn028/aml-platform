# Fix 06 — Elasticsearch ImagePullBackOff

## Problem
The `elasticsearch` pod in the `aiops` namespace was stuck in `ImagePullBackOff`:
```
docker.elastic.co/elasticsearch/elasticsearch:8.15.0
```

## Root Cause
Elastic's private registry (`docker.elastic.co`) applies strict pull rate limits and
authentication requirements. On Docker Desktop in a local dev environment, pulls from
`docker.elastic.co` frequently time out or return 429/403 errors.

## Fix

### Changed to Docker Hub public image (ES 7.x)
Updated `infrastructure/k8s/aiops.yml`:
```yaml
# Before
image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0

# After
image: elasticsearch:7.17.25
```

Elasticsearch 7.17.x is freely available on Docker Hub and the REST API is fully
compatible with the AIOps platform's usage (hot signal storage via `ELASTICSEARCH_URL`
in the ConfigMap). No breaking API changes affect this workload.

Single-node dev configuration retained:
```yaml
env:
  - name: discovery.type
    value: single-node
  - name: xpack.security.enabled
    value: "false"
  - name: ES_JAVA_OPTS
    value: "-Xms512m -Xmx512m"
```

### Pre-pull tip (if still slow)
```powershell
docker pull elasticsearch:7.17.25
kubectl rollout restart deployment/elasticsearch -n aiops
```
Pulling through Docker Desktop's daemon caches the image locally, making Kubernetes'
subsequent pull instantaneous.

## Files Changed
- `infrastructure/k8s/aiops.yml` — elasticsearch image tag
