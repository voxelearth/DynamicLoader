@echo off
setlocal EnableExtensions EnableDelayedExpansion

REM =============================================================================
REM Voxelearth Windows Setup (Velocity + Paper + DynamicLoader)
REM Mirrors setup.sh logic for Windows, including Velocity auto-download
REM =============================================================================

goto :MAIN

REM --------------------------- Helper: set_prop ---------------------------------
REM Usage: call :set_prop "file" "key" "value"
:set_prop
setlocal
set "SP_FILE=%~1"
set "SP_KEY=%~2"
set "SP_VAL=%~3"

if not exist "%SP_FILE%" type nul > "%SP_FILE%"

> "%SP_FILE%.tmp" (
  for /f "usebackq delims=" %%L in ("%SP_FILE%") do (
    set "line=%%L"
    setlocal EnableDelayedExpansion
    echo !line! | findstr /b /c:"%SP_KEY%=" >nul
    if not errorlevel 1 (
      echo %SP_KEY%=%SP_VAL%
    ) else (
      echo !line!
    )
    endlocal
  )
)
REM If key was never found, append it
findstr /b /c:"%SP_KEY%=" "%SP_FILE%.tmp" >nul || >> "%SP_FILE%.tmp" echo %SP_KEY%=%SP_VAL%

move /y "%SP_FILE%.tmp" "%SP_FILE%" >nul
endlocal & exit /b 0

REM --------------------------- MAIN --------------------------------------------
:MAIN

REM --- Free ports 25565 (Velocity) and 25566 (Paper) ---
echo [*] Freeing ports 25565 and 25566 if busy...
for %%P in (25565 25566) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr /R /C:":%%P[ ]" ^| findstr LISTENING') do (
    echo     - Killing PID %%A on port %%P ...
    taskkill /F /PID %%A >nul 2>nul
  )
)

REM --- Check Java (JDK 21+) using winget if needed ---
echo [*] Checking Java (JDK 21+)...
where java >nul 2>nul
if errorlevel 1 (
  echo [!] Java not found in PATH.
  where winget >nul 2>nul
  if errorlevel 1 (
    echo [!] winget not available. Please install Temurin JDK 21 manually, then re-run this script.
    goto :END
  )
  echo [*] Installing Temurin JDK 21 via winget...
  winget install -e --id EclipseAdoptium.Temurin.21.JDK --silent
) else (
  echo [OK] Java detected:
  java -version 2>&1
)

REM --- Basic project paths ---
set "ROOT_DIR=%CD%"
set "VELOCITY_DIR=%ROOT_DIR%\velocity"
set "SERVER_DIR=%VELOCITY_DIR%\server"
set "ITEMS_DIR=%ROOT_DIR%\velocity-server-folder-items"

set "ZIP_PATH=%ITEMS_DIR%\voxelearth.zip"
set "LOBBY_ZIP=%ITEMS_DIR%\lobby.zip"
set "FORWARD_SECRET=%ITEMS_DIR%\forwarding.secret"
set "SPAWN_SCRIPT=%ITEMS_DIR%\spawn_server.py"
set "VELOCITY_TOML=%ITEMS_DIR%\velocity.toml"
set "SERVER_ICON=%ITEMS_DIR%\server-icon.png"
set "PROTOCOLIZE_JAR=%ITEMS_DIR%\protocolize-velocity.jar"

REM --- Velocity download config ---
set "PROJECT=velocity"
set "VELOCITY_VERSION=3.4.0-SNAPSHOT"
set "USER_AGENT=DynamicLoader/1.0.0 (contact@me.com)"

echo [*] Setting up Velocity + Voxelearth server...
if not exist "%VELOCITY_DIR%" mkdir "%VELOCITY_DIR%"
if not exist "%SERVER_DIR%" mkdir "%SERVER_DIR%"

REM --- Velocity jar: auto-download from /builds/latest ---
set "VELOCITY_JAR=%VELOCITY_DIR%\velocity.jar"
if exist "%VELOCITY_JAR%" (
  echo [*] velocity.jar already exists, skipping download.
) else (
  echo [*] Fetching latest Velocity build info via builds/latest...
  pushd "%VELOCITY_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference = 'Stop';" ^
    "$project = '%PROJECT%';" ^
    "$version = '%VELOCITY_VERSION%';" ^
    "$ua = '%USER_AGENT%';" ^
    "$url = 'https://fill.papermc.io/v3/projects/{0}/versions/{1}/builds/latest' -f $project, $version;" ^
    "Write-Host '[*] Requesting latest Velocity build from:';" ^
    "Write-Host '    ' $url;" ^
    "$resp = Invoke-WebRequest -Uri $url -Headers @{ 'User-Agent' = $ua };" ^
    "$json = $resp.Content | ConvertFrom-Json;" ^
    "if ($null -ne $json.ok -and -not $json.ok) {" ^
    "  $msg = if ($json.message) { $json.message } else { 'Unknown error' };" ^
    "  throw ('Fill API error: ' + $msg);" ^
    "}" ^
    "if (-not $json.downloads) { throw 'No downloads entry in builds/latest response.' }" ^
    "$dl = $json.downloads.'server:default'; if (-not $dl) { $dl = $json.downloads.server }" ^
    "if (-not $dl -or -not $dl.url) { throw 'No server:default download URL in builds/latest response.' }" ^
    "$fileUrl = $dl.url;" ^
    "Write-Host '[*] Downloading Velocity from:';" ^
    "Write-Host '    ' $fileUrl;" ^
    "Invoke-WebRequest -Uri $fileUrl -Headers @{ 'User-Agent' = $ua } -OutFile 'velocity.jar';"
  if errorlevel 1 (
    popd
    echo [!] Error downloading Velocity; aborting.
    goto :END
  ) else (
    popd
    echo [OK] Velocity downloaded successfully to "%VELOCITY_JAR%".
  )
)

REM --- Unzip Voxelearth world/template ---
if not exist "%ZIP_PATH%" (
  echo [!] voxelearth.zip not found: "%ZIP_PATH%"
  goto :END
)
echo [*] Unzipping voxelearth.zip into "%SERVER_DIR%" ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -Path '%ZIP_PATH%' -DestinationPath '%SERVER_DIR%' -Force"

REM --- Unzip lobby world ---
if not exist "%LOBBY_ZIP%" (
  echo [!] lobby.zip not found: "%LOBBY_ZIP%"
  goto :END
)
echo [*] Unzipping lobby.zip into "%SERVER_DIR%" ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -Path '%LOBBY_ZIP%' -DestinationPath '%SERVER_DIR%' -Force"

REM --- Cleanup plugins we don’t want on lobby ---
set "PLUGINS_DIR=%SERVER_DIR%\plugins"
if exist "%PLUGINS_DIR%" (
  echo [*] Removing lobby plugins: VoxelEarth, SecureAutoOP, FAWE...
  del /q "%PLUGINS_DIR%\VoxelEarth.jar" 2>nul
  del /q "%PLUGINS_DIR%\SecureAutoOP-1.1.jar" 2>nul
  del /q "%PLUGINS_DIR%\FastAsyncWorldEdit-*.jar" 2>nul
)

REM --- server.properties: port 25566 & spawn protection 1024 ---
set "SERVER_PROPS=%SERVER_DIR%\server.properties"
if not exist "%SERVER_PROPS%" type nul > "%SERVER_PROPS%"

echo [*] Ensuring server-port=25566 and spawn-protection=1024...
call :set_prop "%SERVER_PROPS%" "server-port" "25566"
call :set_prop "%SERVER_PROPS%" "spawn-protection" "1024"
echo [OK] Updated "%SERVER_PROPS%"

REM --- Copy extra files into Velocity dir (for plugin/runtime) ---
echo [*] Copying forwarding.secret, spawn_server.py, voxelearth.zip, icon, velocity.toml to Velocity dir...
if not exist "%FORWARD_SECRET%" echo [!] Missing "%FORWARD_SECRET%"
if not exist "%SPAWN_SCRIPT%"  echo [!] Missing "%SPAWN_SCRIPT%"
if not exist "%SERVER_ICON%"   echo [!] Missing "%SERVER_ICON%"
if not exist "%VELOCITY_TOML%" echo [!] Missing "%VELOCITY_TOML%"

copy /Y "%FORWARD_SECRET%" "%VELOCITY_DIR%" >nul
copy /Y "%SPAWN_SCRIPT%" "%VELOCITY_DIR%" >nul
copy /Y "%ZIP_PATH%" "%VELOCITY_DIR%" >nul
copy /Y "%SERVER_ICON%" "%VELOCITY_DIR%" >nul
copy /Y "%VELOCITY_TOML%" "%VELOCITY_DIR%" >nul

REM --- DynamicLoader jar: build if not present, then copy to Velocity plugins ---
set "DL_PATTERN=DynamicLoader*.jar"
set "DL_BUILD_DIR=%ROOT_DIR%\build\libs"
set "DL_TARGET_DIR=%VELOCITY_DIR%\plugins"

if not exist "%DL_BUILD_DIR%" mkdir "%DL_BUILD_DIR%"
if not exist "%DL_TARGET_DIR%" mkdir "%DL_TARGET_DIR%"

set "DL_FOUND="
for /f "delims=" %%F in ('dir /b "%DL_BUILD_DIR%\%DL_PATTERN%" 2^>nul') do (
  set "DL_FOUND=%%F"
  goto :HAVE_DL
)

echo [*] DynamicLoader jar not found, running Gradle build...
if exist "%ROOT_DIR%\gradlew.bat" (
  pushd "%ROOT_DIR%"
  call gradlew.bat build
  popd

  for /f "delims=" %%F in ('dir /b "%DL_BUILD_DIR%\%DL_PATTERN%" 2^>nul') do (
    set "DL_FOUND=%%F"
    goto :HAVE_DL
  )
)

if not defined DL_FOUND (
  echo [!] DynamicLoader jar still not found in "%DL_BUILD_DIR%".
  echo     Make sure your Gradle build produces DynamicLoader*.jar.
  goto :AFTER_DL
)

:HAVE_DL
echo [*] Copying DynamicLoader (%DL_FOUND%) to Velocity plugins...
copy /Y "%DL_BUILD_DIR%\%DL_FOUND%" "%DL_TARGET_DIR%" >nul

:AFTER_DL

REM --- Protocolize jar to Velocity plugins ---
if exist "%PROTOCOLIZE_JAR%" (
  echo [*] Copying Protocolize jar to Velocity plugins...
  copy /Y "%PROTOCOLIZE_JAR%" "%DL_TARGET_DIR%" >nul
) else (
  echo [!] Protocolize jar missing: "%PROTOCOLIZE_JAR%"
)

REM --- Enable Velocity in base paper-global.yml (optional, no parentheses to break CMD) ---
set "PAPER_GLOBAL=%SERVER_DIR%\config\paper-global.yml"
if exist "%PAPER_GLOBAL%" (
  goto HAS_PAPER_GLOBAL
) else (
  echo [!] "%PAPER_GLOBAL%" not found; base Velocity toggle will rely on spawn_server.py
  goto AFTER_PAPER_GLOBAL
)

:HAS_PAPER_GLOBAL
echo [*] Optional: enabling proxies.velocity.enabled in "%PAPER_GLOBAL%" ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p = Get-Content -Raw '%PAPER_GLOBAL%';" ^
  "$p = [regex]::Replace($p, '(?m)(^(\s*)velocity:\s*\r?\n\2[ \t]+enabled:\s*)false\b', '${1}true');" ^
  "Set-Content '%PAPER_GLOBAL%' $p"
goto AFTER_PAPER_GLOBAL

:AFTER_PAPER_GLOBAL

REM --- Ensure EULA accepted for base server ---
> "%SERVER_DIR%\eula.txt" echo eula=true

REM --- Check Python for spawn_server.py (Windows side) ---
echo [*] Checking Python 3 for spawn_server.py...
where python >nul 2>nul
if errorlevel 1 (
  where py >nul 2>nul
  if errorlevel 1 (
    echo [!] Python not found. Install Python 3 and ensure "python" or "py" is in PATH.
  ) else (
    echo [OK] You can call spawn_server.py using "py -3" from Velocity.
  )
) else (
  echo [OK] python is available.
)

REM --- Start Paper server (base) in background with log ---
echo [*] Starting base Paper server on port 25566 in background...
pushd "%SERVER_DIR%"
if not exist "paper.jar" (
  echo [!] paper.jar missing in "%SERVER_DIR%". Does your ZIP include paper.jar?
  popd
  goto :NO_PAPER
)
start "" /B cmd /c "java -jar paper.jar > paper_server.log 2>&1"
popd
echo [OK] Paper server started in background.

:NO_PAPER

REM --- Start Velocity in this terminal ---
echo [*] Starting Velocity in this terminal...
cd /d "%VELOCITY_DIR%"
java -jar velocity.jar

echo [✓] Velocity exited. Paper server may still be running in background.
goto :END

:END
endlocal
exit /b 0
