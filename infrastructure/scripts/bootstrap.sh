#!/usr/bin/env bash
# Brings up the local registry and observability stack against Docker Desktop Kubernetes.
set -euo pipefail

echo ">> Switching kubectl context to docker-desktop..."
kubectl config use-context docker-desktop

echo ">> Starting local registry on localhost:5001..."
if docker ps --format '{{.Names}}' | grep -q '^aml-registry$'; then
  echo "   Registry already running."
else
  docker run -d --restart=always --name aml-registry -p 5001:5000 registry:2
  echo "   Registry started."
fi

echo ">> Adding Helm repos..."
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

echo ">> Installing observability stack..."
kubectl create namespace monitoring || true
helm upgrade --install kube-prom-stack prometheus-community/kube-prometheus-stack \
  -n monitoring --wait
helm upgrade --install tempo grafana/tempo -n monitoring --wait
helm upgrade --install loki grafana/loki-stack -n monitoring --wait

echo ">> Done. Grafana at http://localhost:3000"
