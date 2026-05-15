# register-task.ps1  — run once as Administrator
# Registers start-port-forwards.ps1 to run at logon (60 s delay).

$targetUser = $env:USERNAME
$scriptsDir = "D:\ResearchWithDrSatish\aml-platform\scripts"
$pfTask     = "AML-Platform-PortForwards"

$action = New-ScheduledTaskAction `
    -Execute  "powershell.exe" `
    -Argument "-NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$scriptsDir\start-port-forwards.ps1`""

$trigger = New-ScheduledTaskTrigger -AtLogOn -User $targetUser
$trigger.Delay = "PT60S"

$settings = New-ScheduledTaskSettingsSet `
    -ExecutionTimeLimit "00:10:00" `
    -RestartCount 2 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -StartWhenAvailable

Register-ScheduledTask -TaskName $pfTask -Action $action -Trigger $trigger `
    -Settings $settings -RunLevel Highest -Force | Out-Null
Write-Host "Registered: $pfTask (runs 60 s after logon)" -ForegroundColor Green

Write-Host "`nTo run it now without rebooting:"
Write-Host "  Start-ScheduledTask -TaskName '$pfTask'"
Write-Host "`nLog: $scriptsDir\port-forward.log"
