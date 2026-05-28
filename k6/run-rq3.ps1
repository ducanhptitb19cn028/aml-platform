# RQ3 experiment runner.
# Executes rq3-overhead.js at each of the four TRACE_SAMPLE_RATE values,
# patching the Kubernetes ConfigMap and rolling the AML deployments between runs.
#
# Usage (from the k6/ directory):
#   .\run-rq3.ps1
#   .\run-rq3.ps1 -KycUrl http://localhost:8082 -OutputDir results

param(
  [string]$KycUrl    = 'http://localhost:8082',
  [string]$TxnUrl    = 'http://localhost:8081',
  [string]$CaseUrl   = 'http://localhost:8080',
  [string]$Namespace = 'aml',
  [string]$ConfigMap = 'aiops-config',
  [string]$OutputDir = 'results'
)

$ErrorActionPreference = 'Stop'

$rates = @('0.0', '0.1', '0.5', '1.0')

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

foreach ($rate in $rates) {
  $label = $rate -replace '\.', '_'

  Write-Host "`n=== RQ3: TRACE_SAMPLE_RATE = $rate ===" -ForegroundColor Cyan

  # 1. Patch the ConfigMap
  Write-Host "  Patching ConfigMap $ConfigMap in namespace $Namespace..." -ForegroundColor Yellow
  kubectl patch configmap $ConfigMap -n $Namespace --type merge `
    -p "{`"data`":{`"TRACE_SAMPLE_RATE`":`"$rate`"}}"

  # 2. Rolling restart so pods pick up the new env value
  Write-Host "  Rolling restart of AML deployments..." -ForegroundColor Yellow
  kubectl rollout restart deployment -n $Namespace
  kubectl rollout status deployment -n $Namespace --timeout=120s

  # 3. Settle — allow Prometheus to scrape a clean new baseline before measuring
  Write-Host "  Settling for 60 s..." -ForegroundColor Yellow
  Start-Sleep -Seconds 60

  # 4. Run k6
  $outFile = Join-Path $OutputDir "rq3_rate_${label}.json"
  Write-Host "  Running k6 → $outFile" -ForegroundColor Green

  k6 run `
    -e SAMPLE_RATE=$rate `
    -e KYC_URL=$KycUrl `
    -e TXN_URL=$TxnUrl `
    -e CASE_URL=$CaseUrl `
    --out "json=$outFile" `
    rq3-overhead.js

  Write-Host "  Done — results written to $outFile" -ForegroundColor Green
}

Write-Host "`nAll four RQ3 runs complete." -ForegroundColor Cyan
Write-Host "Result files:"
Get-ChildItem $OutputDir -Filter 'rq3_rate_*.json' | ForEach-Object { Write-Host "  $($_.FullName)" }
