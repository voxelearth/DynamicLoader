[CmdletBinding()]
param(
    [ValidateRange(10, 400)]
    [int]$MinimumFreeGB = 75,

    [ValidateRange(1, 720)]
    [int]$PruneAgeHours = 168,

    [ValidatePattern('^[0-9]+(B|KB|MB|GB|TB)$')]
    [string]$BuilderKeepStorage = '10GB',

    [ValidatePattern('^[0-9]+(B|KB|MB|GB|TB)$')]
    [string]$BuilderMaxStorage = '20GB',

    [switch]$ForceCompact
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$maintenanceRoot = Join-Path $env:ProgramData 'VoxelEarth'
$maintenanceLog = Join-Path $maintenanceRoot 'docker-disk-maintenance.log'
New-Item -ItemType Directory -Path $maintenanceRoot -Force | Out-Null

function Write-MaintenanceLog {
    param([Parameter(Mandatory)][string]$Message)
    $line = '{0:o} {1}' -f (Get-Date), $Message
    Add-Content -LiteralPath $maintenanceLog -Value $line -Encoding UTF8
    Write-Output $line
}

function Get-SystemFreeGB {
    $disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
    if (-not $disk) {
        throw 'Unable to query the C: filesystem.'
    }
    return [math]::Round($disk.FreeSpace / 1GB, 2)
}

function Find-DockerDataVhd {
    $wslRoot = Join-Path $env:LOCALAPPDATA 'Docker\wsl'
    $matches = @(Get-ChildItem -LiteralPath $wslRoot -Filter 'docker_data.vhdx' -File -Recurse -ErrorAction Stop)
    if ($matches.Count -ne 1) {
        throw "Expected exactly one Docker data VHDX below $wslRoot; found $($matches.Count)."
    }
    return $matches[0].FullName
}

function Wait-DockerEngine {
    param([int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        & docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    throw "Docker Engine did not become ready within $TimeoutSeconds seconds."
}

function Invoke-VhdCompact {
    param([Parameter(Mandatory)][string]$VhdPath)

    $diskpartScript = Join-Path ([IO.Path]::GetTempPath()) ("voxelearth-compact-{0}.txt" -f [guid]::NewGuid())
    try {
        @(
            "select vdisk file=`"$VhdPath`""
            'compact vdisk'
            'exit'
        ) | Set-Content -LiteralPath $diskpartScript -Encoding ASCII

        $result = & diskpart.exe /s $diskpartScript 2>&1
        $result | ForEach-Object { Write-MaintenanceLog "diskpart: $_" }
        if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'successfully compacted') {
            throw "DiskPart did not report a successful compact operation (exit $LASTEXITCODE)."
        }
    }
    finally {
        Remove-Item -LiteralPath $diskpartScript -Force -ErrorAction SilentlyContinue
    }
}

$vhdPath = Find-DockerDataVhd
$freeBefore = Get-SystemFreeGB
$vhdBefore = [math]::Round((Get-Item -LiteralPath $vhdPath).Length / 1GB, 2)
Write-MaintenanceLog "Starting maintenance: C free=${freeBefore}GB, Docker VHDX=${vhdBefore}GB."

# These commands retain tagged images and all volumes. Build cache is bounded,
# while only dangling images older than the requested age are removed.
& docker image prune --force --filter "until=${PruneAgeHours}h" | ForEach-Object { Write-MaintenanceLog "image-prune: $_" }
if ($LASTEXITCODE -ne 0) { throw "docker image prune failed with exit $LASTEXITCODE." }
& docker builder prune --force --filter "until=${PruneAgeHours}h" `
    --reserved-space $BuilderKeepStorage --max-used-space $BuilderMaxStorage |
    ForEach-Object { Write-MaintenanceLog "builder-prune: $_" }
if ($LASTEXITCODE -ne 0) { throw "docker builder prune failed with exit $LASTEXITCODE." }

# Tell ext4 which blocks are unused before an offline VHDX compact.
& wsl.exe -d docker-desktop -u root -- fstrim -av | ForEach-Object { Write-MaintenanceLog "fstrim: $_" }
if ($LASTEXITCODE -ne 0) { throw "fstrim failed with exit $LASTEXITCODE." }

$freeAfterOnline = Get-SystemFreeGB
if (-not $ForceCompact -and $freeAfterOnline -ge $MinimumFreeGB) {
    Write-MaintenanceLog "Online maintenance is sufficient: C free=${freeAfterOnline}GB; offline compact skipped."
    return
}

$runningContainers = @(& docker ps --format '{{.Names}}')
Write-MaintenanceLog "Free space is below ${MinimumFreeGB}GB (or compact forced); stopping Docker Desktop for VHDX compaction."

$desktopStopped = $false
try {
    & docker desktop stop | ForEach-Object { Write-MaintenanceLog "desktop-stop: $_" }
    if ($LASTEXITCODE -ne 0) { throw "docker desktop stop failed with exit $LASTEXITCODE." }
    $desktopStopped = $true

    & wsl.exe --shutdown
    if ($LASTEXITCODE -ne 0) { throw "wsl --shutdown failed with exit $LASTEXITCODE." }
    Start-Sleep -Seconds 5

    Invoke-VhdCompact -VhdPath $vhdPath
}
finally {
    if ($desktopStopped) {
        & docker desktop start | ForEach-Object { Write-MaintenanceLog "desktop-start: $_" }
        Wait-DockerEngine
        foreach ($container in $runningContainers) {
            & docker start $container *> $null
            if ($LASTEXITCODE -ne 0) {
                Write-MaintenanceLog "WARNING: failed to restart container $container."
            }
        }
    }
}

$freeAfter = Get-SystemFreeGB
$vhdAfter = [math]::Round((Get-Item -LiteralPath $vhdPath).Length / 1GB, 2)
Write-MaintenanceLog "Maintenance complete: C free=${freeAfter}GB, Docker VHDX=${vhdAfter}GB."
