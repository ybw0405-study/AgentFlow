#!/usr/bin/env bash
# 停止本地进程；加 --infra 连容器一起停。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"

for pidfile in "$LOGS"/*.pid; do
  [ -f "$pidfile" ] || continue
  pid="$(cat "$pidfile")"
  kill "$pid" 2>/dev/null || true
  rm -f "$pidfile"
done
pkill -f 'uvicorn app.main' 2>/dev/null || true

if [ "${1:-}" = "--infra" ]; then
  docker compose -f "$ROOT/ops/docker-compose.infra.yml" down
fi
echo 'stopped.'
