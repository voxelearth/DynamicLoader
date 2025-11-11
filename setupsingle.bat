@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================================
REM Voxelearth Windows Setup (non-interactive)
REM =============================================================================

goto :MAIN

REM --------------------------- helper: set_prop --------------------------------
:set_prop
REM Usage: call :set_prop "file" "key" "value"
setlocal
set "SP_FILE=%~1"
set "SP_KEY=%~2"
set "SP_VAL=%~3"
if not exist "%SP_FILE%" type nul > "%SP_FILE%"

findstr /b /i /c:"%SP_KEY%=" "%SP_FILE%" >nul 2>nul
if errorlevel 1 (
  >> "%SP_FILE%" echo %SP_KEY%=%SP_VAL%
  endlocal & exit /b 0
)

> "%SP_FILE%.tmp" (
  for /f "usebackq delims=" %%L in ("%SP_FILE%") do (
    set "line=%%L"
    setlocal EnableDelayedExpansion
    echo !line! | findstr /b /i /c:"%SP_KEY%=" >nul
    if not errorlevel 1 (
      echo %SP_KEY%=%SP_VAL%
    ) else (
      echo !line!
    )
    endlocal
  )
)
move /y "%SP_FILE%.tmp" "%SP_FILE%" >nul
endlocal & exit /b 0


:MAIN
REM --------------------------- Free ports --------------------------------------
echo [*] Freeing ports 25568 and 25569 if busy...
for %%P in (25568 25569) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr /R /C:":%%P[ ]" ^| findstr LISTENING') do (
    echo     - Killing PID %%A on port %%P ...
    taskkill /F /PID %%A >nul 2>nul
  )
)

REM --------------------------- Node.js -----------------------------------------
echo [*] Checking Node.js...
where node >nul 2>nul || goto :NODE_INSTALL
for /f %%A in ('node -v') do set "NODE_VER_STR=%%A"
echo [OK] Node.js %NODE_VER_STR% detected; not upgrading.
goto :AFTER_NODE

:NODE_INSTALL
where winget >nul 2>nul || (
  echo [!] Node.js not found and winget unavailable. Install Node.js LTS from https://nodejs.org/
  goto :AFTER_NODE
)
echo [*] Installing Node.js LTS via winget...
winget install --id OpenJS.NodeJS.LTS -e --source winget --silent >nul 2>nul
:AFTER_NODE

REM --------------------------- Python ------------------------------------------
echo [*] Checking Python 3...
set "PY_CMD="
where py >nul 2>nul && set "PY_CMD=py -3"
if not defined PY_CMD ( where python  >nul 2>nul && set "PY_CMD=python" )
if not defined PY_CMD ( where python3 >nul 2>nul && set "PY_CMD=python3" )

if not defined PY_CMD (
  where winget >nul 2>nul || (
    echo [!] Python 3 not found and winget unavailable. Install Python 3 from https://www.python.org/
    goto :AFTER_PY
  )
  echo [*] Installing Python 3.11 via winget...
  winget install -e --id Python.Python.3.11 --silent >nul 2>nul
  set "PY_CMD="
  where py >nul 2>nul && set "PY_CMD=py -3"
  if not defined PY_CMD ( where python  >nul 2>nul && set "PY_CMD=python" )
  if not defined PY_CMD ( where python3 >nul 2>nul && set "PY_CMD=python3" )
  if not defined PY_CMD (
    echo [!] Could not find Python after install; continuing without Python packages.
    goto :AFTER_PY
  )
)

echo [OK] Using %PY_CMD%
for /f "delims=" %%V in ('%PY_CMD% --version 2^>^&1') do echo     %%V
echo [*] Ensuring pip and packages...
%PY_CMD% -m ensurepip --upgrade >nul 2>nul
%PY_CMD% -m pip3 --version >nul 2>nul || %PY_CMD% -m ensurepip --upgrade >nul 2>nul
%PY_CMD% -m pip3 install --upgrade pip >nul 2>nul
%PY_CMD% -m pip3 install --user --upgrade numpy tqdm requests urllib3 >nul 2>nul

:AFTER_PY

REM --------------------------- Java 17+ ----------------------------------------
echo [*] Checking Java (JRE 17+)...
where java >nul 2>nul && goto :JAVA_FOUND

where winget >nul 2>nul || (
  echo [!] Java not found and winget unavailable. Install Java 17+ (Adoptium Temurin JRE recommended).
  goto :AFTER_JAVA
)
echo [*] Installing Temurin JRE 17 via winget...
winget install -e --id EclipseAdoptium.Temurin.17.JRE --silent >nul 2>nul
goto :AFTER_JAVA

:JAVA_FOUND
echo [OK] Java found:
REM IMPORTANT: run this OUTSIDE of parentheses to avoid ". was unexpected" errors.
java -version 2>&1

:AFTER_JAVA

REM =============================== Server config ===============================
set "ROOT_DIR=%CD%"
set "SERVER_DIR=%ROOT_DIR%\server"
set "ZIP_PATH=%ROOT_DIR%\velocity-server-folder-items\voxelearth.zip"
set "SERVER_JAR=paper.jar"

if not defined XMS set "XMS=2G"
if not defined XMX set "XMX=4G"

echo:
echo [*] Setting up Voxelearth Paper server...
if not exist "%SERVER_DIR%" mkdir "%SERVER_DIR%"

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

> "%SERVER_DIR%\eula.txt" echo eula=true

set "CUDA_DIR=%SERVER_DIR%\cuda_voxelizer"
if exist "%CUDA_DIR%" (
  echo [*] Making cuda_voxelizer writable for Everyone...
  set "ACE=*S-1-1-0:(OI)(CI)F"
  icacls "%CUDA_DIR%" /grant "%ACE%" /T >nul 2>nul
  attrib -R /S /D "%CUDA_DIR%\*" >nul 2>nul
)

if not exist "%SERVER_DIR%\%SERVER_JAR%" (
  echo ERROR: "%SERVER_DIR%\%SERVER_JAR%" is missing. Does the ZIP include %SERVER_JAR%?
  exit /b 3
)

echo:
echo [*] Starting Paper (Voxelearth) on port 25569...
cd /d "%SERVER_DIR%"

set "JAVA_FLAGS=-Xms%XMS% -Xmx%XMX% -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=100"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=30"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:G1HeapRegionSize=4M -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=20"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:G1MixedGCLiveThresholdPercent=85 -XX:MaxTenuringThreshold=1 -XX:+DisableExplicitGC"
set "JAVA_FLAGS=%JAVA_FLAGS% -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem -Daikars.new.flags=true"

REM Foreground start. For background+log:
REM start "" /B cmd /c "java %JAVA_FLAGS% -jar "%SERVER_JAR%" ^> server.log 2^>^&1"
java %JAVA_FLAGS% -jar "%SERVER_JAR%"

echo [OK] Paper server stopped (or started successfully if no errors were shown).
goto :EOF

:END
endlocal
exit /b 0
