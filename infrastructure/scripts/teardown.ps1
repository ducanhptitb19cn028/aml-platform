$ErrorActionPreference = 'Continue'

Write-Host ">> Removing observability stack (namespace delete covers all resources)..."
kubectl delete namespace monitoring --ignore-not-found

# Clean up cluster-scoped resources left by kube-prometheus-stack
Write-Host ">> Removing cluster-scoped kube-prom-stack resources..."
kubectl delete clusterrole,clusterrolebinding `
    -l app.kubernetes.io/instance=kube-prom-stack --ignore-not-found 2>$null
kubectl delete clusterrole,clusterrolebinding `
    -l release=kube-prom-stack --ignore-not-found 2>$null

Write-Host ">> Stopping local registry..."
docker rm -f aml-registry 2>$null; $true

Write-Host ">> Done."
