#!/usr/bin/env bash
set -euo pipefail

ROOT="/root/autodl-fs/Annotation-Platform/frontend-vue"
PORT="${ANNOTATION_FRONTEND_PORT:-6006}"
HOST="0.0.0.0"
LOG_DIR="/root/autodl-fs/Annotation-Platform/logs"
LOG_FILE="$LOG_DIR/frontend-vite-${PORT}.log"
PID_FILE="$LOG_DIR/frontend-vite-${PORT}.pid"
PORT_FILE="$ROOT/.frontend_service_port"

mkdir -p "$LOG_DIR"

export NVM_DIR="/root/.nvm"
if [ -s "$NVM_DIR/nvm.sh" ]; then
  # shellcheck disable=SC1091
  . "$NVM_DIR/nvm.sh"
  nvm use 18 >/dev/null 2>&1 || true
fi
export PATH="/root/.nvm/versions/node/v18.20.8/bin:$PATH"

if [ ! -d "$ROOT" ]; then
  echo "frontend root not found: $ROOT" >&2
  exit 1
fi

if [ ! -f "$ROOT/node_modules/vite/bin/vite.js" ]; then
  echo "vite entry not found. Please install frontend dependencies in $ROOT" >&2
  exit 1
fi

if ss -lnt "( sport = :$PORT )" | grep -q ":$PORT"; then
  if pgrep -f "$ROOT/node_modules/.bin/vite --host $HOST --port $PORT|$ROOT/node_modules/vite/bin/vite.js --host $HOST --port $PORT" >/dev/null 2>&1; then
    echo "$PORT" > "$PORT_FILE"
    echo "frontend already listening on $HOST:$PORT"
    exit 0
  fi
  echo "port $PORT is already occupied by another process" >&2
  ss -lntp "( sport = :$PORT )" >&2 || true
  exit 1
fi

cd "$ROOT"
setsid node "$ROOT/node_modules/vite/bin/vite.js" --host "$HOST" --port "$PORT" \
  > "$LOG_FILE" 2>&1 < /dev/null &

echo "$!" > "$PID_FILE"
echo "$PORT" > "$PORT_FILE"
echo "frontend started on $HOST:$PORT with pid $(cat "$PID_FILE")"
