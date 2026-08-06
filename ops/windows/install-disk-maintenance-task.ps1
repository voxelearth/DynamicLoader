[CmdletBinding()]
param(
    [string]$TaskName = 'VoxelEarth Docker Disk Maintenance',
    [ValidateSet('Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday')]
    [string]$DayOfWeek = 'Sunday',
    [ValidatePattern('^([01]\d|2[0-3]):[0-5]\d$')]
    [string]$At = '04:00'
)

$ErrorActionPreference = 'Stop'
$maintenanceScript = Join-Path $PSScriptRoot 'docker-disk-maintenance.ps1'
if (-not (Test-Path -LiteralPath $maintenanceScript -PathType Leaf)) {
    throw "Maintenance script not found: $maintenanceScript"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$action = New-ScheduledTaskAction `
    -Execute 'powershell.exe' `
    -Argument ("-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"{0}`"" -f $maintenanceScript)
$trigger = New-ScheduledTaskTrigger -Weekly -WeeksInterval 1 -DaysOfWeek $DayOfWeek -At $At
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -WakeToRun `
    -ExecutionTimeLimit (New-TimeSpan -Hours 2) `
    -RestartCount 2 `
    -RestartInterval (New-TimeSpan -Minutes 15)

$task = New-ScheduledTask -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
    -Description 'Bounds Docker cache, trims ext4, and compacts the Docker Desktop VHDX only when host free space is low.'
Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null
Write-Output "Installed scheduled task '$TaskName' for $DayOfWeek at $At as $identity."
