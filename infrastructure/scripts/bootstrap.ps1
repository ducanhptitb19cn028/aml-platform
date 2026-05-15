# Brings up the local registry and observability stack against Docker Desktop Kubernetes.
#
# WINDOWS NOTE: If helm.exe is blocked by an Application Control policy, run this
# script directly from an elevated (Admin) PowerShell terminal — do NOT call it via
# "make cluster-up" which spawns a non-interactive subprocess that inherits the block:
#
#   powershell -ExecutionPolicy Bypass -File infrastructure/scripts/bootstrap.ps1
#
$ErrorActionPreference = 'Stop'

Write-Host ">> Switching kubectl context to docker-desktop..."
kubectl config use-context docker-desktop

Write-Host ">> Starting local registry on localhost:5001..."
$running = docker ps --format '{{.Names}}' | Where-Object { $_ -eq 'aml-registry' }
if ($running) {
    Write-Host "   Registry already running."
} else {
    docker run -d --restart=always --name aml-registry -p 5001:5000 registry:2
    Write-Host "   Registry started."
}

Write-Host ">> Adding Helm repos..."
helm.exe repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm.exe repo add grafana https://grafana.github.io/helm-charts
helm.exe repo update

Write-Host ">> Installing observability stack..."
kubectl create namespace monitoring 2>$null; $true
helm.exe upgrade --install kube-prom-stack prometheus-community/kube-prometheus-stack -n monitoring --wait
helm.exe upgrade --install tempo grafana/tempo -n monitoring --wait
helm.exe upgrade --install loki grafana/loki-stack -n monitoring --set grafana.enabled=false --wait

Write-Host ">> Provisioning Loki + Tempo datasources in Grafana..."
kubectl apply -f infrastructure/k8s/grafana-datasources.yml
kubectl rollout restart deployment -n monitoring -l app.kubernetes.io/name=grafana

Write-Host ">> Done. Grafana at http://localhost:3000 (allow ~30s for datasource sidecar to load)"
