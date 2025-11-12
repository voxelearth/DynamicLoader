#!/bin/bash
set -e

# Run apt-get as root when needed
SUDO=""; [ "$EUID" -ne 0 ] && SUDO="sudo"

confirm() {
  # Usage: confirm "[?] message [Y/n]"
  local prompt="${1:-Proceed? [Y/n]}"; local ans
  if [ -t 0 ]; then
    read -r -p "$prompt " ans
    [[ -z "$ans" || "$ans" =~ ^[Yy]$ ]]
  else
    # Non-interactive (e.g., CI): default to "no"
    return 1
  fi
}

# --- Kill any processes using ports 25568 or 25569 ---
echo "[*] Checking and freeing ports 25568 and 25569..."
for port in 25568 25569; do
  pid=$(sudo lsof -ti:$port || true)
  if [ -n "$pid" ]; then
    echo "    Killing process on port $port (PID $pid)..."
    sudo kill -9 $pid || true
  fi
done

# --- Ensure JDK is installed (21+ required for Velocity) ---
check_jdk() {
  if ! command -v javac >/dev/null 2>&1; then
    echo "[!] JDK not found (OpenJDK 21 required)."
    if confirm "[?] Install OpenJDK 21 now? [Y/n]"; then
      $SUDO apt-get update
      $SUDO apt-get install -y openjdk-21-jdk
    else
      echo "[!] JDK 21 is required; aborting."
      exit 1
    fi
  else
    JDK_VERSION=$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)
    if [ "$JDK_VERSION" -lt 21 ]; then
      echo "[!] JDK version $JDK_VERSION found; Velocity requires 21+."
      if confirm "[?] Install OpenJDK 21 now? [Y/n]"; then
        $SUDO apt-get install -y openjdk-21-jdk
      else
        echo "[!] Continuing with existing JDK (may fail)."
      fi
    else
      echo "[✓] JDK version $JDK_VERSION OK"
    fi
  fi
}

# --- Ensure basic system dependencies (prompt before install) ---
install_deps() {
  echo "[*] Checking core system dependencies..."

  # Map commands to apt packages to avoid reinstalling existing deps
  declare -A need=(
    [unzip]=unzip
    [jq]=jq
    [curl]=curl
    [mvn]=maven
  )

  missing_pkgs=()
  for cmd in "${!need[@]}"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "    - $cmd missing (needs ${need[$cmd]})"
      missing_pkgs+=("${need[$cmd]}")
    else
      echo "[✓] $cmd found"
    fi
  done

  if [ ${#missing_pkgs[@]} -eq 0 ]; then
    return
  fi

  echo "[!] Missing packages: ${missing_pkgs[*]}"
  if confirm "[?] Install these now via apt-get? [Y/n]"; then
    $SUDO apt-get update
    $SUDO apt-get install -y --no-install-recommends "${missing_pkgs[@]}"
    echo "[✓] System dependencies installed."
  else
    echo "[!] Cannot continue without: ${missing_pkgs[*]}"
    exit 1
  fi
}

# --- Run all checks ---
check_jdk
install_deps

# --- Configuration ---
ROOT_DIR="$(pwd)"
SERVER_DIR="$ROOT_DIR/server"
ZIP_PATH="$ROOT_DIR/velocity-server-folder-items/voxelearth.zip"
SERVER_JAR="paper.jar"

# Optional env overrides:
#   XMS=2G XMX=4G RCON_PORT=25575 RCON_PASSWORD=secret ./run.sh
XMS="${XMS:-2G}"
XMX="${XMX:-4G}"
RCON_PORT="${RCON_PORT:-}"
RCON_PASSWORD="${RCON_PASSWORD:-}"

echo "[*] Setting up Voxelearth Paper server..."
mkdir -p "$SERVER_DIR"

# --- Unzip Voxelearth (contains paper.jar, plugins/, worlds/, etc.) ---
echo "[*] Unzipping voxelearth.zip into $SERVER_DIR..."
unzip -qo "$ZIP_PATH" -d "$SERVER_DIR"

# --- server.properties helpers (replace or append) ---
set_prop() {
  local file="$1" key="$2" val="$3"
  touch "$file"
  if grep -q "^${key}=" "$file"; then
    sed -i.bak "s|^${key}=.*|${key}=${val}|" "$file"
  else
    echo "${key}=${val}" >> "$file"
  fi
}

# --- Set core props: port + distances and optional RCON ---
SERVER_PROPS="$SERVER_DIR/server.properties"
echo "[*] Ensuring server.properties values..."
set_prop "$SERVER_PROPS" "server-port" "25569"
set_prop "$SERVER_PROPS" "view-distance" "32"
set_prop "$SERVER_PROPS" "simulation-distance" "6"

if [ -n "$RCON_PORT" ] && [ -n "$RCON_PASSWORD" ]; then
  echo "[*] Enabling RCON on port $RCON_PORT"
  set_prop "$SERVER_PROPS" "enable-rcon" "true"
  set_prop "$SERVER_PROPS" "rcon.port" "$RCON_PORT"
  set_prop "$SERVER_PROPS" "rcon.password" "$RCON_PASSWORD"
fi

# --- Accept the EULA (non-interactive startup) ---
echo "eula=true" > "$SERVER_DIR/eula.txt"

# --- Make cuda_voxelizer executable if present ---
CUDA_DIR="$SERVER_DIR/cuda_voxelizer"
if [ -e "$CUDA_DIR" ]; then
  echo "[*] Making cuda_voxelizer writable/executable (chmod -R 777)..."
  chmod -R 777 "$CUDA_DIR" || true
fi

# --- Sanity check for server jar ---
if [ ! -f "$SERVER_DIR/$SERVER_JAR" ]; then
  echo "ERROR: $SERVER_DIR/$SERVER_JAR is missing. Does voxelearth.zip include $SERVER_JAR?" >&2
  exit 3
fi

# --- Start Paper directly (no proxy); background with log file ---
echo "[*] Starting Paper (Voxelearth) on port 25569..."
cd "$SERVER_DIR"

# Aikar-style JVM flags
JAVA_FLAGS=(
  "-Xms${XMS}" "-Xmx${XMX}"
  "-XX:+UseG1GC" "-XX:+ParallelRefProcEnabled" "-XX:MaxGCPauseMillis=100"
  "-XX:+UnlockExperimentalVMOptions" "-XX:G1NewSizePercent=20" "-XX:G1MaxNewSizePercent=30"
  "-XX:G1HeapRegionSize=4M" "-XX:G1ReservePercent=15" "-XX:InitiatingHeapOccupancyPercent=20"
  "-XX:G1MixedGCLiveThresholdPercent=85" "-XX:MaxTenuringThreshold=1" "-XX:+DisableExplicitGC"
  "-XX:+AlwaysPreTouch" "-XX:+PerfDisableSharedMem" "-Daikars.new.flags=true"
)

java "${JAVA_FLAGS[@]}" -jar "$SERVER_JAR"

echo "[✓] Paper server started successfully."
