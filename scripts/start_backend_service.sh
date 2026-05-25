#!/usr/bin/env bash
set -euo pipefail

ROOT="/root/autodl-fs/Annotation-Platform"
PORT="${ANNOTATION_BACKEND_PORT:-8080}"
JAR="$ROOT/backend-springboot/target/platform-backend-1.0.0.jar"
LOG_DIR="$ROOT/logs"
LOG_FILE="$LOG_DIR/backend-springboot-${PORT}.log"
PID_FILE="$LOG_DIR/backend-springboot-${PORT}.pid"

mkdir -p "$LOG_DIR"

if [ ! -f "$JAR" ]; then
  echo "backend jar not found: $JAR" >&2
  exit 1
fi

if ss -lnt "( sport = :$PORT )" | grep -q ":$PORT"; then
  if pgrep -f "java -jar $JAR --server.port=$PORT" >/dev/null 2>&1; then
    echo "backend already listening on 0.0.0.0:$PORT"
    exit 0
  fi
  echo "port $PORT is already occupied by another process" >&2
  ss -lntp "( sport = :$PORT )" >&2 || true
  exit 1
fi

cd "$ROOT"
setsid java -jar "$JAR" --server.port="$PORT" > "$LOG_FILE" 2>&1 < /dev/null &

echo "$!" > "$PID_FILE"
echo "backend started on 0.0.0.0:$PORT with pid $(cat "$PID_FILE")"
