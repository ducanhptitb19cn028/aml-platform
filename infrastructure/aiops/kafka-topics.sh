#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Creates all AIOps Kafka topics.
# Run after the main stack is up.
#
# Usage:
#   ./kafka-topics.sh
#
# Override defaults:
#   KAFKA_CONTAINER=kafka BOOTSTRAP=localhost:9092 ./kafka-topics.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
BOOTSTRAP="${BOOTSTRAP:-localhost:9092}"

create() {
  local topic="$1"
  local partitions="${2:-4}"
  echo "Creating topic: ${topic} (partitions=${partitions})"
  docker exec "${KAFKA_CONTAINER}" \
    kafka-topics \
      --bootstrap-server "${BOOTSTRAP}" \
      --create \
      --if-not-exists \
      --topic "${topic}" \
      --partitions "${partitions}" \
      --replication-factor 1
}

echo "=== Creating AIOps Kafka topics on ${KAFKA_CONTAINER} (${BOOTSTRAP}) ==="

# Telemetry ingestion topics (high-volume → 4 partitions)
create aiops.telemetry.metrics 4
create aiops.telemetry.traces  4
create aiops.telemetry.logs    4

# Feature records from stream-processor
create aiops.features 4

# ML engine output — scored incidents (lower volume → 2 partitions)
create aiops.incidents 2

# Decision-engine output
create aiops.decisions 2

# Remediation execution audit
create aiops.actions 2

# Feedback loop — labelled outcomes
create aiops.outcomes 2

echo ""
echo "=== All AIOps topics created successfully ==="
docker exec "${KAFKA_CONTAINER}" \
  kafka-topics \
    --bootstrap-server "${BOOTSTRAP}" \
    --list | grep "^aiops\." | sort
