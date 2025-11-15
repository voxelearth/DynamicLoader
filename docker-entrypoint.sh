#!/bin/bash
set -euo pipefail

ROOT_DIR="/opt/dynamicloader"
VELOCITY_DIR="$ROOT_DIR/velocity"
SERVER_DIR="$VELOCITY_DIR/server"
LOG_DIR="$ROOT_DIR/docker-logs"
mkdir -p "$LOG_DIR"
PAPER_LOG="$LOG_DIR/paper.log"
VELOCITY_LOG="$LOG_DIR/velocity.log"
touch "$PAPER_LOG" "$VELOCITY_LOG"

cd "$ROOT_DIR"
if [ ! -f "$VELOCITY_DIR/velocity.jar" ] || [ ! -f "$SERVER_DIR/paper.jar" ]; then
  echo "[Docker] Velocity or server files missing; running setup.sh ..."
  AUTO_CONFIRM=1 SKIP_SERVER_START=1 ./setup.sh
fi

PAPER_PID=""
VELOCITY_PID=""
TAIL_PID=""

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

shutdown_children() {
  for pid in "$TAIL_PID" "$PAPER_PID" "$VELOCITY_PID"; do
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
  exit "$code"
}

trap 'handle_exit 130' INT
trap 'handle_exit 143' TERM

start_paper
start_velocity

tail -F "$PAPER_LOG" "$VELOCITY_LOG" &
TAIL_PID=$!

set +e
wait -n "$PAPER_PID" "$VELOCITY_PID"
EXIT_CODE=$?
echo "[Docker] One of the services stopped (code $EXIT_CODE). Shutting down..."
handle_exit "$EXIT_CODE"
