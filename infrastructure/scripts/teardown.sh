#!/usr/bin/env bash
set -euo pipefail

echo ">> Removing observability stack..."
helm uninstall kube-prom-stack -n monitoring || true
helm uninstall tempo          -n monitoring || true
helm uninstall loki           -n monitoring || true
kubectl delete namespace monitoring --ignore-not-found

echo ">> Stopping local registry..."
docker rm -f aml-registry || true

echo ">> Done."
