# Fix 02 — Replace Bitnami Kafka with Apache Kafka (KRaft mode)

## Problem
`helm upgrade --install kafka bitnami/kafka` timed out. Bitnami's Kafka chart post-August 2025
requires `bitnami/kafka:4.0.0-debian-12-r10`, which is unavailable due to Bitnami image
access restrictions. Even with `controller.replicaCount=1`, the image pull fails.

## Root Cause
Bitnami restricted access to their container images for non-subscribers after August 2025.

## Fix

### Created `infrastructure/k8s/kafka-dev.yml`
Single-node Apache Kafka in KRaft mode using the public `apache/kafka:3.9.0` image.

Key configuration:
```yaml
image: apache/kafka:3.9.0
env:
  - name: KAFKA_NODE_ID
    value: "1"
  - name: KAFKA_PROCESS_ROLES
    value: broker,controller
  - name: CLUSTER_ID
    value: MkU3OEVBNTcwNTJENDM2Qk
  - name: KAFKA_ADVERTISED_LISTENERS
    value: PLAINTEXT://kafka.data.svc.cluster.local:9092
```

StatefulSet name: `kafka`, pod always named `kafka-0`.
Services: `kafka` (ClusterIP :9092) and `kafka-headless` (headless).

### Updated `Makefile` — k8s-data target
```makefile
kubectl apply -f infrastructure/k8s/kafka-dev.yml
kubectl rollout status statefulset/kafka -n $(DATA_NS) --timeout=180s
```

### Fixed `make k8s-topics` — Windows cmd.exe incompatibilities
- Removed `$(kubectl ...)` shell substitution (not supported in cmd.exe)
- Removed `-l app=kafka` label selector (hardcoded `kafka-0` — StatefulSet pod name is predictable)
- Used full path `/opt/kafka/bin/kafka-topics.sh` (not on default PATH in apache/kafka image)

```makefile
k8s-topics:
    kubectl exec -n $(DATA_NS) kafka-0 -- bash -c "\
        /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic aiops.telemetry.metrics --partitions 6 && \
        ..."
```

## Files Changed
- `infrastructure/k8s/kafka-dev.yml` (new)
- `Makefile` — k8s-data, k8s-topics targets
