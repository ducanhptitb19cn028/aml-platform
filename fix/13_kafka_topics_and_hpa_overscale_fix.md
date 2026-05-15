# Fix 13 — Missing Kafka Topics + HPA Over-Scaling Starves Node

## Problem A — Missing `aiops.features` Topic
The ml-engine Kafka consumer logged:
```
ERROR Kafka consumer error:
  KafkaError{code=UNKNOWN_TOPIC_OR_PART, val=3,
  str="Subscribed topic not available: aiops.features: Broker: Unknown topic or partition"}
```
No `FeatureRecord` messages were ever consumed, so no incidents were scored
and the dashboard stayed at **0 events received**.

### Root Cause
The topic `aiops.features` (stream-processor → ml-engine) was never created.
Also missing: `aiops.telemetry.logs` and `aiops.telemetry.traces`.

### Fix
```bash
kubectl exec -n data kafka-0 -- sh -c "
PATH=/opt/kafka/bin:\$PATH
kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic aiops.features         --partitions 3 --replication-factor 1 --if-not-exists
kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic aiops.telemetry.logs   --partitions 3 --replication-factor 1 --if-not-exists
kafka-topics.sh --bootstrap-server localhost:9092 --create \
  --topic aiops.telemetry.traces --partitions 3 --replication-factor 1 --if-not-exists
"
```
Then restart ml-engine and stream-processor to re-subscribe:
```powershell
kubectl rollout restart deployment/ml-engine deployment/stream-processor -n aiops
```

---

## Problem B — HPA Over-Scaling Exhausts Node Memory
After fixing the Kafka topic, the ml-engine pod stayed `Pending` with:
```
0/1 nodes are available: 1 Insufficient memory.
```

### Root Cause
The AIOps remediation engine's `SCALE_OUT` action increments `minReplicas` on
each HPA by `scaleOutReplicas` (default 2). Over multiple incident cycles this
accumulated to:

| Service | minPods | maxPods | Memory/pod | Total |
|---|---|---|---|---|
| customer-kyc | 20 | 20 | 768 Mi | 15 Gi |
| transaction-monitoring | 15 | 15 | 768 Mi | 11.25 Gi |
| case-management | 10 | 10 | 768 Mi | 7.5 Gi |

Total requested ≈ **34 Gi** on a **16 Gi** Docker Desktop node. Dozens of
stale ReplicaSets from old rollouts compounded the problem with additional
pending pods that consumed scheduling slots.

### Fix
Reset HPAs to sensible dev values and scale deployments down:
```powershell
# Reset HPAs
kubectl patch hpa customer-kyc          -n aml --type=merge -p '{"spec":{"minReplicas":1,"maxReplicas":3}}'
kubectl patch hpa transaction-monitoring -n aml --type=merge -p '{"spec":{"minReplicas":1,"maxReplicas":3}}'
kubectl patch hpa case-management        -n aml --type=merge -p '{"spec":{"minReplicas":1,"maxReplicas":3}}'

# Scale deployments to 1 (HPA will scale up as needed within budget)
kubectl scale deployment customer-kyc transaction-monitoring case-management -n aml --replicas=1
```

### Prevention
`RemediationService` clamps `minReplicas` to `maxReplicas` before patching the
HPA, but `maxReplicas` itself is never capped. For a single-node dev cluster,
`SCALE_OUT` remediation should not increase `maxReplicas` beyond 3.
Consider adding a configurable upper bound:
```yaml
aiops:
  remediation:
    scale-out-replicas: 1
    scale-out-max-replicas: 3   # hard ceiling for SCALE_OUT actions
```

## Files Changed
- None (operational fix — Kafka topic creation + HPA patch)
