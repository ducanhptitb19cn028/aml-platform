# start-port-forwards.ps1
# Waits for Docker Desktop Kubernetes to be ready, then starts all port-forwards.
# Registered as a Task Scheduler task that runs at user logon.

$LogFile = "$PSScriptRoot\port-forward.log"
$MaxWaitSeconds = 180
$RetryInterval = 10

function Write-Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $msg"
    Add-Content -Path $LogFile -Value $line
}

Write-Log "=== AML Platform port-forward startup ==="

# ── 1. Wait for Kubernetes API to be reachable ────────────────────────────────
Write-Log "Waiting for Kubernetes API (up to ${MaxWaitSeconds}s)..."
$elapsed = 0
while ($elapsed -lt $MaxWaitSeconds) {
    $result = kubectl cluster-info 2>&1
    if ($LASTEXITCODE -eq 0) { break }
    Start-Sleep -Seconds $RetryInterval
    $elapsed += $RetryInterval
}
if ($elapsed -ge $MaxWaitSeconds) {
    Write-Log "ERROR: Kubernetes did not become ready in ${MaxWaitSeconds}s. Exiting."
    exit 1
}
Write-Log "Kubernetes ready after ${elapsed}s."

# ── 2. Helper: start a single port-forward in a hidden window ─────────────────
function Start-PF($ns, $svc, $ports) {
    $args = @("port-forward", "-n", $ns, "svc/$svc", $ports)
    Start-Process -FilePath "kubectl" -ArgumentList $args `
                  -WindowStyle Hidden -PassThru | Out-Null
    Write-Log "  pf: $svc  $ports"
}

# ── 3. AML services ───────────────────────────────────────────────────────────
Write-Log "Starting AML port-forwards..."
Start-PF "aml" "case-management"        "8080:8080"
Start-PF "aml" "transaction-monitoring" "8081:8081"
Start-PF "aml" "customer-kyc"           "8082:8082"

# ── 4. AIOps services ─────────────────────────────────────────────────────────
Write-Log "Starting AIOps port-forwards..."
Start-PF "aiops" "telemetry-collector" "9001:9001"
Start-PF "aiops" "stream-processor"   "9002:9002"
Start-PF "aiops" "ml-engine"          "8000:8000"
Start-PF "aiops" "llm-engine"         "8001:8001"
Start-PF "aiops" "ollama"             "11434:11434"
Start-PF "aiops" "decision-engine"    "9003:9003"
Start-PF "aiops" "remediation-engine" "9004:9004"
Start-PF "aiops" "alerting-service"   "9005:9005"
Start-PF "aiops" "feedback-service"   "9006:9006"
Start-PF "aiops" "aiops-dashboard"    "3001:80"

# ── 5. Observability stack ────────────────────────────────────────────────────
Write-Log "Starting observability port-forwards..."
Start-PF "monitoring" "kube-prom-stack-kube-prome-prometheus" "9090:9090"
Start-PF "monitoring" "kube-prom-stack-grafana"               "3000:80"
Start-PF "monitoring" "loki"                                   "3100:3100"
Start-PF "monitoring" "tempo"                                  "3200:3200"

Write-Log "All port-forwards launched."
Write-Log "  Dashboard:  http://localhost:3001"
Write-Log "  Grafana:    http://localhost:3000"
Write-Log "  Prometheus: http://localhost:9090"
