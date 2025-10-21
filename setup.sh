#!/bin/bash
set -e

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
    echo "[*] JDK not found. Installing OpenJDK 21..."
    sudo apt update && sudo apt install -y openjdk-21-jdk
  else
    JDK_VERSION=$(javac -version 2>&1 | awk '{print $2}' | cut -d. -f1)
    if [ "$JDK_VERSION" -lt 21 ]; then
      echo "[!] JDK version $JDK_VERSION found. Velocity requires JDK 21+."
      read -p "[?] Reinstall OpenJDK 21? [Y/n] " yn
      case "$yn" in
        [Yy]*|"" ) sudo apt install -y openjdk-21-jdk ;;
        * ) echo "[!] Skipping JDK reinstall." ;;
      esac
    else
      echo "[✓] JDK version $JDK_VERSION OK"
    fi
  fi
}

# --- Ensure basic system dependencies (auto-install if missing) ---
install_deps() {
  echo "[*] Checking core system dependencies..."
  for dep in unzip jq curl maven python3 python3-pip npm; do
    if ! command -v $dep >/dev/null 2>&1; then
      echo "[*] Installing missing dependency: $dep"
      sudo apt update && sudo apt install -y $dep
    else
      echo "[✓] $dep found"
    fi
  done
}

# --- Check Node.js version (warn and prompt if too old) ---
check_node() {
  if ! command -v node >/dev/null 2>&1; then
    echo "[*] Node.js not found. Installing latest LTS via apt (may be old)..."
    sudo apt update && sudo apt install -y nodejs npm
  else
    NODE_VERSION=$(node -v | sed 's/v//; s/\..*//')
    if [ "$NODE_VERSION" -lt 14 ]; then
      echo "[!] Node.js version $(node -v) is too old for modern JS (needs >=14)."
      read -p "[?] Replace with modern version via NVM? [Y/n] " yn
      case "$yn" in
        [Yy]*|"" )
          echo "[*] Installing NVM and latest LTS Node.js..."
          curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash
          export NVM_DIR="$HOME/.nvm"
          [ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
          nvm install --lts
          ;;
        * ) echo "[!] Skipping Node.js update." ;;
      esac
    else
      echo "[✓] Node.js version $(node -v) OK"
    fi
  fi
}

# --- Ensure Python packages are installed (auto-install if missing) ---
install_python_packages() {
  echo "[*] Checking Python packages: numpy, tqdm, requests"
  missing=()
  for pkg in numpy tqdm requests; do
    python3 -c "import $pkg" 2>/dev/null || missing+=($pkg)
  done
  if [ ${#missing[@]} -gt 0 ]; then
    echo "[*] Installing missing Python packages: ${missing[*]}"
    pip3 install --user "${missing[@]}"
  else
    echo "[✓] All Python packages present"
  fi
}

# --- Run all checks ---
check_jdk
install_deps
check_node
install_python_packages

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

# --- Modify port ---
echo "[*] Setting server.properties port to 25566..."
SERVER_PROPS="$SERVER_DIR/server.properties"
if [ -f "$SERVER_PROPS" ]; then
  sed -i.bak 's/^server-port=.*/server-port=25566/' "$SERVER_PROPS"
else
  echo "server-port=25566" > "$SERVER_PROPS"
fi

# --- Copy extra files ---
echo "[*] Copying forwarding.secret, spawn_server.py, voxelearth.zip, and velocity.toml..."
cp "$FORWARD_SECRET" "$SPAWN_SCRIPT" "$ZIP_PATH" "$VELOCITY_TOML" "$VELOCITY_DIR/"

# --- Update velocity.toml with correct Windows IP ---
WINDOWS_IP=$(ip route | grep -i default | awk '{print $3}')
echo "[*] Updating velocity.toml with Windows host IP $WINDOWS_IP:25566..."
VELOCITY_TOML_PATH="$VELOCITY_DIR/velocity.toml"

# Replace existing lobby line or add if missing
if grep -q '^lobby\s*=' "$VELOCITY_TOML_PATH"; then
    sed -i "s|^lobby\s*=.*|lobby = \"$WINDOWS_IP:25566\"|" "$VELOCITY_TOML_PATH"
else
    echo "lobby = \"$WINDOWS_IP:25566\"" >> "$VELOCITY_TOML_PATH"
fi

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

# --- Ensure Windows has Java ---
echo "[*] Checking if Java exists on Windows PATH..."
cmd.exe /C "powershell -Command \
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { \
    Write-Host 'Java not found. Installing Temurin JDK 21...'; \
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --silent; \
} else { Write-Host 'Java found on Windows.' }"

# --- Start Paper (Windows side) ---
echo "[*] Launching Paper server in Windows terminal..."
PAPER_PATH="$(wslpath -w "$SERVER_DIR")"
cmd.exe /C start "Paper Server" powershell -NoExit -Command "cd '$PAPER_PATH'; java -jar paper.jar"

# --- Start Velocity (stay in Ubuntu) ---
echo "[*] Starting Velocity in this terminal..."
cd "$VELOCITY_DIR"
java -jar velocity.jar

echo "[✓] Velocity exited. Paper server is still running in Windows."
