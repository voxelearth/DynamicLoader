#!/bin/bash
set -euo pipefail

ROOT_DIR="/opt/dynamicloader"
VELOCITY_DIR="$ROOT_DIR/velocity"
SERVER_DIR="$VELOCITY_DIR/server"
LOG_DIR="$ROOT_DIR/docker-logs"
SESSIONS_DIR="$VELOCITY_DIR/servers"
PROXY_PORT="${PROXY_PORT:-25565}"
LOBBY_PORT="${LOBBY_PORT:-25566}"
INTERNAL_LOG_MAX_BYTES="${INTERNAL_LOG_MAX_BYTES:-104857600}"
export PROXY_PORT LOBBY_PORT
mkdir -p "$LOG_DIR"
PAPER_LOG="$LOG_DIR/paper.log"
VELOCITY_LOG="$LOG_DIR/velocity.log"

cleanup_stale_runtime() {
  # Session processes cannot survive a container restart. Remove directories
  # left by a hard host/container stop before Velocity starts a new warm pool.
  if [ -d "$SESSIONS_DIR" ]; then
    find "$SESSIONS_DIR" -mindepth 1 -maxdepth 1 -xdev -exec rm -rf -- {} +
  fi
  mkdir -p "$SESSIONS_DIR"

  # /tmp is private, disposable container state. Older images allowed every
  # Paper process to leave uniquely named native-library files here.
  if [ -d /tmp ]; then
    find /tmp -mindepth 1 -maxdepth 1 -xdev -exec rm -rf -- {} +
  fi

  # Docker's logging driver is the durable, bounded log sink. These two files
  # only multiplex the child processes into stdout, so start them clean.
  : >"$PAPER_LOG"
  : >"$VELOCITY_LOG"
}

cleanup_stale_runtime

cd "$ROOT_DIR"
if [ ! -f "$VELOCITY_DIR/velocity.jar" ] || [ ! -f "$SERVER_DIR/paper.jar" ]; then
  echo "[Docker] Velocity or server files missing; running setup.sh ..."
  AUTO_CONFIRM=1 SKIP_SERVER_START=1 ./setup.sh
fi

configure_ports() {
  local props="$SERVER_DIR/server.properties"
  if [ -f "$props" ]; then
    if grep -q '^server-port=' "$props"; then
      sed -i "s/^server-port=.*/server-port=${LOBBY_PORT}/" "$props"
    else
      echo "server-port=${LOBBY_PORT}" >> "$props"
    fi
    if grep -q '^query.port=' "$props"; then
      sed -i "s/^query.port=.*/query.port=${LOBBY_PORT}/" "$props"
    fi
  fi

  local vtoml="$VELOCITY_DIR/velocity.toml"
  if [ -f "$vtoml" ]; then
    python3 - <<'PY'
from pathlib import Path
import os
vtoml = Path(os.environ["VELOCITY_TOML"])
proxy_port = os.environ["PROXY_PORT"]
lobby_port = os.environ["LOBBY_PORT"]
text = vtoml.read_text(encoding="utf-8").splitlines()
def patch_line(line, prefix, value):
    if line.startswith(prefix):
        return f'{prefix}{value}"'
    return line
for i, line in enumerate(text):
    if line.startswith("bind = "):
        text[i] = f'bind = "0.0.0.0:{proxy_port}"'
    elif line.strip().startswith('lobby = "'):
        text[i] = f'lobby = "127.0.0.1:{lobby_port}"'
    elif line.startswith("port = ") and "[query]" in text[max(0, i-5):i+1]:
        text[i] = f"port = {proxy_port}"
vtoml.write_text("\n".join(text) + "\n", encoding="utf-8")
PY
  fi
  echo "[Docker] Proxy port set to ${PROXY_PORT}, lobby port ${LOBBY_PORT}"
}
export VELOCITY_TOML="$VELOCITY_DIR/velocity.toml"
configure_ports

PAPER_PID=""
VELOCITY_PID=""
TAIL_PID=""
LOG_JANITOR_PID=""

start_paper() {
  echo "[Docker] Starting Paper lobby server..."
  (
    cd "$SERVER_DIR"
    exec stdbuf -oL -eL java -jar paper.jar --nogui
  ) >>"$PAPER_LOG" 2>&1 &
  PAPER_PID=$!
  echo "[Docker] Paper PID $PAPER_PID"
}

start_velocity() {
  echo "[Docker] Starting Velocity proxy..."
  (
    cd "$VELOCITY_DIR"
    exec stdbuf -oL -eL java -jar velocity.jar
  ) >>"$VELOCITY_LOG" 2>&1 &
  VELOCITY_PID=$!
  echo "[Docker] Velocity PID $VELOCITY_PID"
}

start_log_janitor() {
  (
    while true; do
      sleep 60
      for log_file in "$PAPER_LOG" "$VELOCITY_LOG"; do
        [ -f "$log_file" ] || continue
        log_size="$(stat -c %s "$log_file" 2>/dev/null || echo 0)"
        if [ "$log_size" -gt "$INTERNAL_LOG_MAX_BYTES" ] 2>/dev/null; then
          : >"$log_file"
          echo "[Docker] Truncated internal relay log $log_file at $log_size bytes"
        fi
      done
    done
  ) &
  LOG_JANITOR_PID=$!
}

shutdown_children() {
  for pid in "$LOG_JANITOR_PID" "$TAIL_PID" "$PAPER_PID" "$VELOCITY_PID"; do
    if [ -n "${pid:-}" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
}

handle_exit() {
  code=$1
  echo "[Docker] Stopping child processes..."
  shutdown_children
  wait "$PAPER_PID" 2>/dev/null || true
  wait "$VELOCITY_PID" 2>/dev/null || true
  wait "$LOG_JANITOR_PID" 2>/dev/null || true
  exit "$code"
}

trap 'handle_exit 130' INT
trap 'handle_exit 143' TERM

start_paper
start_velocity
start_log_janitor

tail -F "$PAPER_LOG" "$VELOCITY_LOG" &
TAIL_PID=$!

set +e
wait -n "$PAPER_PID" "$VELOCITY_PID"
EXIT_CODE=$?
echo "[Docker] One of the services stopped (code $EXIT_CODE). Shutting down..."
handle_exit "$EXIT_CODE"
