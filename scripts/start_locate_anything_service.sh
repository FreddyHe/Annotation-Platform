#!/usr/bin/env bash
set -euo pipefail

ROOT="/root/autodl-fs/Annotation-Platform"
PYTHON="${LOCATE_ANYTHING_PYTHON:-/root/miniconda3/envs/LLM_DL/bin/python}"
PORT="${LOCATE_ANYTHING_PORT:-5010}"
HOST="${LOCATE_ANYTHING_HOST:-0.0.0.0}"
LOG_DIR="$ROOT/logs"
LOG_FILE="$LOG_DIR/locate-anything-${PORT}.log"
PID_FILE="$LOG_DIR/locate-anything-${PORT}.pid"

mkdir -p "$LOG_DIR"

if [ ! -x "$PYTHON" ]; then
  echo "LocateAnything python not executable: $PYTHON" >&2
  exit 1
fi

if ss -lnt "( sport = :$PORT )" | grep -q ":$PORT"; then
  if pgrep -f "locate_anything_model_server.py" >/dev/null 2>&1; then
    echo "LocateAnything already listening on $HOST:$PORT"
    exit 0
  fi
  echo "port $PORT is already occupied by another process" >&2
  ss -lntp "( sport = :$PORT )" >&2 || true
  exit 1
fi

if [ -f "$PID_FILE" ] && ! kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  rm -f "$PID_FILE"
fi

export CUDA_VISIBLE_DEVICES="${LOCATE_ANYTHING_CUDA_VISIBLE_DEVICES:-1}"
export LOCATE_ANYTHING_PORT="$PORT"
export LOCATE_ANYTHING_MODEL_PATH="${LOCATE_ANYTHING_MODEL_PATH:-/root/autodl-fs/models/LocateAnything-3B}"
export LOCATE_ANYTHING_CODE_ROOT="${LOCATE_ANYTHING_CODE_ROOT:-/root/autodl-fs/external/Eagle/Embodied}"
export LOCATE_ANYTHING_DEVICE="${LOCATE_ANYTHING_DEVICE:-cuda}"
export LOCATE_ANYTHING_DTYPE="${LOCATE_ANYTHING_DTYPE:-bfloat16}"
export LOCATE_ANYTHING_MAX_NEW_TOKENS="${LOCATE_ANYTHING_MAX_NEW_TOKENS:-512}"

cd "$ROOT"
nohup setsid "$PYTHON" "$ROOT/algorithm-service/locate_anything_model_server.py" \
  >> "$LOG_FILE" 2>&1 < /dev/null &
echo "$!" > "$PID_FILE"

echo "LocateAnything started on $HOST:$PORT with pid $(cat "$PID_FILE")"
echo "log: $LOG_FILE"
