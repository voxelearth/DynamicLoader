@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================================
REM Voxelearth Windows Setup (non-interactive)
REM - Frees ports 25568/25569
REM - Ensures Node.js (no upgrade if present), Python3, and pip packages
REM - Unzips server zip, sets server.properties, writes EULA
REM - Makes cuda_voxelizer writable (fixing (CI)F parsing)
REM - Starts paper.jar with Aikar flags
REM =============================================================================

REM ---------------------------
REM Free ports 25568 / 25569
REM ---------------------------
echo [*] Freeing ports 25568 and 25569 if busy...
for %%P in (25568 25569) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr /R /C:":%%P[ ]" ^| findstr LISTENING') do (
    echo     - Killing PID %%A on port %%P ...
    taskkill /F /PID %%A >nul 2>nul
  )
)

REM ---------------------------
REM Node.js (detect only; do not upgrade if present)
REM ---------------------------
echo [*] Checking Node.js...
where node >nul 2>nul
if errorlevel 1 (
  echo [!] Node.js not found.
  where winget >nul 2>nul
  if errorlevel 1 (
    echo [!] winget not available; please install Node.js LTS from https://nodejs.org/
  ) else (
    echo [*] Installing Node.js LTS via winget...
    winget install --id OpenJS.NodeJS.LTS -e --source winget --silent >nul 2>nul
  )
) else (
  for /f "tokens=2 delims=v" %%A in ('node -v') do set "NODE_VER=%%A"
  echo [✓] Node.js v!NODE_VER! detected; not upgrading.
)

REM ---------------------------
REM Python 3 + pip + packages
REM ---------------------------
echo [*] Checking Python 3...
set "PY_CMD="
where py >nul 2>nul && set "PY_CMD=py -3"
if "%PY_CMD%"=="" ( where python >nul 2>nul && set "PY_CMD=python" )

if "%PY_CMD%"=="" (
  where winget >nul 2>nul
  if errorlevel 1 (
    echo [!] Python 3 not found and winget unavailable. Install Python 3 from https://www.python.org/
  ) else (
    echo [*] Installing Python 3.11 via winget...
    winget install -e --id Python.Python.3.11 >nul 2>nul
    where py >nul 2>nul && set "PY_CMD=py -3"
    if "%PY_CMD%"=="" ( where python >nul 2>nul && set "PY_CMD=python" )
  )
)

if not "%PY_CMD%"=="" (
  for /f "tokens=2 delims= " %%A in ('%PY_CMD% --version 2^>^&1') do set "PY_VER=%%A"
  echo [✓] Python %PY_VER% detected.
  echo [*] Ensuring pip and packages...
  %PY_CMD% -m ensurepip --upgrade >nul 2>nul
  %PY_CMD% -m pip install --upgrade pip >nul 2>nul
  %PY_CMD% -m pip install --user --upgrade numpy tqdm requests urllib3
)

REM =============================================================================
REM Server configuration
REM =============================================================================
set "ROOT_DIR=%CD%"
set "SERVER_DIR=%ROOT_DIR%\server"
set "ZIP_PATH=%ROOT_DIR%\velocity-server-folder-items\voxelearth.zip"
set "SERVER_JAR=paper.jar"

REM Optional env overrides (set before running this script):
REM   set XMS=2G
REM   set XMX=4G
REM   set RCON_PORT=25575
REM   set RCON_PASSWORD=secret
if not defined XMS set "XMS=2G"
if not defined XMX set "XMX=4G"

echo:
echo [*] Setting up Voxelearth Paper server...
if not exist "%SERVER_DIR%" mkdir "%SERVER_DIR%"

REM --- Unzip Voxelearth zip ---
if not exist "%ZIP_PATH%" (
  echo [!] ZIP not found: %ZIP_PATH%
  echo     Make sure velocity-server-folder-items\voxelearth.zip exists.
  goto :END
)
echo [*] Unzipping "%ZIP_PATH%" into "%SERVER_DIR%" ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Try { Expand-Archive -Path '%ZIP_PATH%' -DestinationPath '%SERVER_DIR%' -Force } Catch { exit 1 }"
if errorlevel 1 (
  echo [!] Unzip failed. Ensure PowerShell 'Expand-Archive' is available.
  goto :END
)

REM --- server.properties helper ---
set "SERVER_PROPS=%SERVER_DIR%\server.properties"
if not exist "%SERVER_PROPS%" ( type nul > "%SERVER_PROPS%" )

call :set_prop "%SERVER_PROPS%" "server-port" "25569"
call :set_prop "%SERVER_PROPS%" "view-distance" "32"
call :set_prop "%SERVER_PROPS%" "simulation-distance" "6"

if defined RCON_PORT if defined RCON_PASSWORD (
  echo [*] Enabling RCON on port %RCON_PORT%
  call :set_prop "%SERVER_PROPS%" "enable-rcon" "true"
  call :set_prop "%SERVER_PROPS%" "rcon.port" "%RCON_PORT%"
  call :set_prop "%SERVER_PROPS%" "rcon.password" "%RCON_PASSWORD%"
)

REM --- EULA ---
> "%SERVER_DIR%\eula.txt" echo eula=true

REM --- Make cuda_voxelizer writable; escape (OI)(CI) for batch block parsing ---
set "CUDA_DIR=%SERVER_DIR%\cuda_voxelizer"
if exist "%CUDA_DIR%" (
  echo [*] Making cuda_voxelizer writable for Everyone...
  icacls "%CUDA_DIR%" /grant *S-1-1-0:^((OI)^(CI)F^) /T >nul 2>nul
  attrib -R /S /D "%CUDA_DIR%\*" >nul 2>nul
)

REM --- Check paper.jar ---
if not exist "%SERVER_DIR%\%SERVER_JAR%" (
  echo ERROR: "%SERVER_DIR%\%SERVER_JAR%" is missing. Does the ZIP include %SERVER_JAR%?
  exit /b 3
)

REM --- Start Paper ---
echo:
echo [*] Starting Paper (Voxelearth) on port 25569...
cd /d "%SERVER_DIR%"

set "JAVA_FLAGS=-Xms%XMS% -Xmx%XMX% -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=30"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:G1HeapRegionSize=4M -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=20"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:G1MixedGCLiveThresholdPercent=85 -XX:MaxTenuringThreshold=1 -XX:+DisableExplicitGC"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem -Daikars.new.flags=true"

REM Foreground start. For background+log, use:
REM   start "" /B cmd /c "java %JAVA_FLAGS% -jar "%SERVER_JAR%" ^> server.log 2^>^&1"
java %JAVA_FLAGS% -jar "%SERVER_JAR%"

echo [✓] Paper server stopped (or started successfully if no errors were shown).
goto :EOF

REM ---------------------------
REM set_prop "file" "key" "value"
REM Replace or append key=value using PowerShell (handles spaces safely)
REM ---------------------------
:set_prop
set "_f=%~1"
set "_k=%~2"
set "_v=%~3"
if not exist "%_f%" ( type nul > "%_f%" )
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$f='%_f%';$k='%_k%';$v='%_v%';" ^
  "if(-not (Test-Path $f)){New-Item -ItemType File -Path $f -Force|Out-Null};" ^
  "$txt = (Get-Content $f -Raw); " ^
  "if($txt -match \"(?m)^$k=\"){ ($txt -replace \"(?m)^$k=.*\",\"$k=$v\") | Set-Content $f -Encoding UTF8 } " ^
  "else { Add-Content $f \"$k=$v\" }"
goto :EOF

:END
endlocal
