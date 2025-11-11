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

# --- Kill any processes using ports 25565 or 25566 ---
echo "[*] Checking and freeing ports 25565 and 25566..."
for port in 25565 25566; do
  pid=$(sudo lsof -ti:$port || true)
  if [ -n "$pid" ]; then
    echo "    Killing process on port $port (PID $pid)..."
    sudo kill -9 $pid || true
  fi
done

# --- Check for CUDA Toolkit 11 ---
check_cuda() {
  echo "[*] Checking for CUDA Toolkit 11..."
  if command -v nvcc >/dev/null 2>&1; then
    CUDA_VERSION=$(nvcc --version | grep -oP "release \K[0-9]+\.[0-9]+" || echo "0")
    if [[ "$CUDA_VERSION" == 11.* ]]; then
      echo "[✓] CUDA Toolkit $CUDA_VERSION detected."
      return
    else
      echo "[!] CUDA version $CUDA_VERSION detected — expected 11.x."
    fi
  else
    echo "[!] CUDA Toolkit not found."
  fi

  read -p "[?] Install CUDA Toolkit 11.0.2 now? [Y/n] " yn
  case "$yn" in
    [Yy]*|"" )
      echo "[*] Installing CUDA Toolkit 11.0.2..."
      sudo apt update
      sudo apt install -y gcc-9 g++-9
      sudo ln -sf /usr/bin/gcc-9 /usr/bin/gcc
      sudo ln -sf /usr/bin/g++-9 /usr/bin/g++
      wget -q http://developer.download.nvidia.com/compute/cuda/11.0.2/local_installers/cuda_11.0.2_450.51.05_linux.run -O cuda_installer.run
      sudo sh cuda_installer.run --silent --toolkit
      rm -f cuda_installer.run
      echo "[✓] CUDA Toolkit 11.0.2 installed successfully."
      ;;
    * )
      echo "[!] Skipping CUDA installation. CUDA-dependent features may fail."
      ;;
  esac
}

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
    [npm]=npm
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

# --- Check Node.js version (warn and prompt if too old) ---
check_node() {
  if ! command -v node >/dev/null 2>&1; then
    echo "[!] Node.js not found."
    if confirm "[?] Install Node.js + npm via apt-get? [Y/n]"; then
      $SUDO apt-get update
      $SUDO apt-get install -y nodejs npm
    else
      echo "[!] Skipping Node.js install."
    fi
  else
    NODE_MAJOR=$(node -v | sed 's/^v//; s/\..*$//')
    if [ "$NODE_MAJOR" -lt 14 ]; then
      echo "[!] Node.js version $(node -v) is too old for modern JS (needs >=14)."
      if confirm "[?] Install latest LTS via NVM now? [Y/n]"; then
        curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
        export NVM_DIR="$HOME/.nvm"
        [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
        nvm install --lts
      else
        echo "[!] Continuing with existing Node.js (may fail)."
      fi
    else
      echo "[✓] Node.js version $(node -v) OK"
    fi
  fi
}

# --- Run all checks ---
check_jdk
install_deps
check_node

# --- Configuration ---
PROJECT="velocity"
VELOCITY_VERSION="3.4.0-SNAPSHOT"
USER_AGENT="DynamicLoader/1.0.0 (contact@me.com)"
ROOT_DIR="$(pwd)"
VELOCITY_DIR="$ROOT_DIR/velocity"
SERVER_DIR="$VELOCITY_DIR/server"
ZIP_PATH="$ROOT_DIR/velocity-server-folder-items/voxelearth.zip"
LOBBY_ZIP="$ROOT_DIR/velocity-server-folder-items/lobby.zip"
FORWARD_SECRET="$ROOT_DIR/velocity-server-folder-items/forwarding.secret"
SPAWN_SCRIPT="$ROOT_DIR/velocity-server-folder-items/spawn_server.py"
VELOCITY_TOML="$ROOT_DIR/velocity-server-folder-items/velocity.toml"
SERVER_ICON="$ROOT_DIR/velocity-server-folder-items/server-icon.png"
PROTOCOLIZE_JAR="$ROOT_DIR/velocity-server-folder-items/protocolize-velocity.jar"

echo "[*] Setting up Velocity + Voxelearth server..."
mkdir -p "$VELOCITY_DIR" "$SERVER_DIR"

# --- Download Velocity JAR using official API ---
cd "$VELOCITY_DIR"
if [ ! -f "velocity.jar" ]; then
  echo "[*] Fetching latest Velocity build info..."
  VERSION_JSON=$(curl -s -H "User-Agent: $USER_AGENT" "https://fill.papermc.io/v3/projects/${PROJECT}/versions/${VELOCITY_VERSION}/builds")

  # Verify JSON isn't an error
  if echo "$VERSION_JSON" | jq -e '.ok == false' >/dev/null 2>&1; then
    ERROR_MSG=$(echo "$VERSION_JSON" | jq -r '.message // "Unknown error"')
    echo "Error fetching Velocity build info: $ERROR_MSG"
    exit 1
  fi

  VELOCITY_URL=$(echo "$VERSION_JSON" | jq -r 'first(.[] | select(.channel == "STABLE") | .downloads."server:default".url) // "null"')

  if [ "$VELOCITY_URL" = "null" ]; then
    echo "No stable Velocity build found for $VELOCITY_VERSION."
    exit 1
  fi

  echo "[*] Downloading Velocity from:"
  echo "    $VELOCITY_URL"
  curl -L -A "$USER_AGENT" -o velocity.jar "$VELOCITY_URL"
  echo "[✓] Velocity downloaded successfully."
else
  echo "[*] velocity.jar already exists, skipping download."
fi

# --- Unzip Voxelearth ---
echo "[*] Unzipping voxelearth.zip..."
unzip -qo "$ZIP_PATH" -d "$SERVER_DIR"

# --- Unzip Lobby World ---
echo "[*] Unzipping lobby.zip..."
unzip -qo "$LOBBY_ZIP" -d "$SERVER_DIR"

# --- Delete VoxelEarth.jar from plugins ---
echo "[*] Removing VoxelEarth.jar from lobby server..."
PLUGINS_DIR="$SERVER_DIR/plugins"
if [ -d "$PLUGINS_DIR" ]; then
  find "$PLUGINS_DIR" -type f -name "VoxelEarth.jar" -delete
fi

# --- Delete SecureAutoOP-1.1.jar from plugins ---
echo "[*] Removing SecureAutoOP-1.1.jar from lobby server..."
if [ -d "$PLUGINS_DIR" ]; then
  find "$PLUGINS_DIR" -type f -name "SecureAutoOP-1.1.jar" -delete
fi

# --- Delete FAWE from plugins ---
echo "[*] Removing FAWE from lobby server..."
if [ -d "$PLUGINS_DIR" ]; then
  find "$PLUGINS_DIR" -type f -name "FastAsyncWorldEdit-*.jar" -delete
fi

# --- Set server port and spawn protection together ---
echo "[*] Ensuring server-port=25566 and spawn-protection=1024..."
SERVER_PROPS="$SERVER_DIR/server.properties"
touch "$SERVER_PROPS"

# replace if present
sed -i.bak \
  -e 's/^server-port=.*/server-port=25566/' \
  -e 's/^spawn-protection=.*/spawn-protection=1024/' \
  "$SERVER_PROPS"

# append if missing
grep -q '^server-port=' "$SERVER_PROPS" || echo 'server-port=25566' >> "$SERVER_PROPS"
grep -q '^spawn-protection=' "$SERVER_PROPS" || echo 'spawn-protection=1024' >> "$SERVER_PROPS"

echo "[✓] Updated $SERVER_PROPS (backup at server.properties.bak)"

# --- Copy extra files ---
echo "[*] Copying forwarding.secret, spawn_server.py, voxelearth.zip, server icon, and velocity.toml..."
cp "$FORWARD_SECRET" "$SPAWN_SCRIPT" "$ZIP_PATH" "$SERVER_ICON" "$VELOCITY_TOML" "$VELOCITY_DIR/"

# --- Update velocity.toml with correct Windows IP ---
# WINDOWS_IP=$(ip route | grep -i default | awk '{print $3}')
# echo "[*] Updating velocity.toml with Windows host IP $WINDOWS_IP:25566..."
# VELOCITY_TOML_PATH="$VELOCITY_DIR/velocity.toml"

# # Replace existing lobby line or add if missing
# if grep -q '^lobby\s*=' "$VELOCITY_TOML_PATH"; then
#     sed -i "s|^lobby\s*=.*|lobby = \"$WINDOWS_IP:25566\"|" "$VELOCITY_TOML_PATH"
# else
#     echo "lobby = \"$WINDOWS_IP:25566\"" >> "$VELOCITY_TOML_PATH"
# fi

# --- Add our build/libs/DynamicLoader*.jar to Velocity plugins ---
# --- if it exists, otherwise, build it first. ---
DYNAMIC_LOADER_JAR_PATTERN="DynamicLoader*.jar"
DYNAMIC_LOADER_JAR_PATH=$(find "$ROOT_DIR/build/libs" -type f -name "$DYNAMIC_LOADER_JAR_PATTERN" | head -n 1)
if [ -z "$DYNAMIC_LOADER_JAR_PATH" ]; then
    echo "[*] DynamicLoader jar not found, building project..."
    cd "$ROOT_DIR"
    ./gradlew build
    DYNAMIC_LOADER_JAR_PATH=$(find "$ROOT_DIR/build/libs" -type f -name "$DYNAMIC_LOADER_JAR_PATTERN" | head -n 1)
    if [ -z "$DYNAMIC_LOADER_JAR_PATH" ]; then
        echo "Error: DynamicLoader jar still not found after build."
        exit 1
    fi
fi
echo "[*] Copying DynamicLoader jar to Velocity plugins..."
mkdir -p "$VELOCITY_DIR/plugins"
cp "$DYNAMIC_LOADER_JAR_PATH" "$VELOCITY_DIR/plugins/"

echo "[*] Copying Protocolize jar to Velocity plugins..."
cp "$PROTOCOLIZE_JAR" "$VELOCITY_DIR/plugins/"

# --- Flip Paper global: proxies.velocity.enabled -> true ---
PAPER_GLOBAL="$SERVER_DIR/config/paper-global.yml"
if [ -f "$PAPER_GLOBAL" ]; then
  # Limit to the proxies -> velocity block, then switch only the enabled line
  sed -i -E '/^[[:space:]]*proxies:[[:space:]]*$/,/^[^[:space:]]/ {
    /^[[:space:]]*velocity:[[:space:]]*$/,/^[[:space:]]{2}[a-zA-Z_-]+:/ s/^([[:space:]]*enabled:[[:space:]]*)false([[:space:]]*)$/\1true\2/
  }' "$PAPER_GLOBAL"
  echo "[*] Enabled Velocity support in $PAPER_GLOBAL"
else
  echo "[!] paper-global.yml not found at $PAPER_GLOBAL"
fi

# --- Ensure Windows has Java ---
echo "[*] Checking if Java exists on Windows PATH..."
cmd.exe /C "powershell -Command \
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { \
    Write-Host 'Java not found. Installing Temurin JDK 21...'; \
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --silent; \
} else { Write-Host 'Java found on Windows.' }"

# --- Start Paper (Windows side) ---
echo "[*] Launching Paper server in Windows terminal..."
# PAPER_PATH="$(wslpath -w "$SERVER_DIR")"
# cmd.exe /C start "Paper Server" powershell -NoExit -Command "cd '$PAPER_PATH'; java -jar paper.jar"
# Launch paper inside WSL to avoid issues with paths
PAPER_PATH="$SERVER_DIR"
# IN LINUX a new subprocess is created to run the paper server in background
(
  cd "$PAPER_PATH"
  echo "[*] Starting Paper server in background..."
  nohup java -jar paper.jar > paper_server.log 2>&1 &
)
echo "[✓] Paper server started in background."


# --- Start Velocity (stay in Ubuntu) ---
echo "[*] Starting Velocity in this terminal..."
cd "$VELOCITY_DIR"
java -jar velocity.jar

echo "[✓] Velocity exited. Paper server is still running in Windows."
