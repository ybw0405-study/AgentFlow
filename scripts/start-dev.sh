#!/usr/bin/env bash
# AgentFlow 一键启动（WSL2 / Linux）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

echo '==> [1/5] 基础设施'
docker compose -f "$ROOT/ops/docker-compose.infra.yml" up -d

echo '==> [2/5] 等待迁移/授权'
for i in $(seq 1 60); do
  code="$(docker inspect -f '{{.State.ExitCode}}' agentflow-grants 2>/dev/null || echo pending)"
  [ "$code" = "0" ] && break
  sleep 3
done
docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -proot agentflow < "$ROOT/scripts/seed-demo.sql" || true

echo '==> [3/5] 构建 Java 服务'
(cd "$ROOT" && mvn -q -DskipTests -Dcheckstyle.skip package)

export AF_INTERNAL_TOKEN="${AF_INTERNAL_TOKEN:-dev-internal-token}"
export AF_JWT_SECRET="${AF_JWT_SECRET:-dev-only-secret-key-0123456789abcdef}"
# 本地走明文 HTTP，refresh Cookie 不能带 Secure，否则浏览器不回传
export AF_COOKIE_SECURE="${AF_COOKIE_SECURE:-false}"
# 本地全量 E2E 一轮会触发数十次执行；产品默认仍是 20 次/60s/用户
export AF_RATE_LIMIT_TRIGGER_MAX="${AF_RATE_LIMIT_TRIGGER_MAX:-300}"

# 真实模型（可选）。显式设置 AF_LLM_* 时直接使用；未设置而其厂商密钥已在环境里时按该厂商派生，
# 免得每次手工导出。密钥只留在进程环境，不写入仓库与磁盘；两者都没有时引擎保持 mock。
if [ -z "${AF_LLM_API_KEY:-}" ] && [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  export AF_LLM_BASE_URL="${AF_LLM_BASE_URL:-https://api.deepseek.com/v1}"
  export AF_LLM_API_KEY="$DEEPSEEK_API_KEY"
  export AF_LLM_MODEL="${AF_LLM_MODEL:-deepseek-flash}"
fi

echo '==> [4/5] engine-api :8000'
PY="$ROOT/engine-api/.venv/Scripts/python.exe"
[ -x "$PY" ] || PY="$ROOT/engine-api/.venv/bin/python"
(cd "$ROOT/engine-api" && nohup "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 \
  > "$LOGS/engine.out.log" 2> "$LOGS/engine.err.log" & echo $! > "$LOGS/engine.pid")

for svc in workflow-service:8084 agent-gateway:8080 audit-service:8086 connector-service:8085; do
  name="${svc%%:*}"
  echo "==> [4/5] $name"
  (cd "$ROOT" && nohup java -jar "$name/target/$name-0.1.0.jar" \
    > "$LOGS/$name.out.log" 2> "$LOGS/$name.err.log" & echo $! > "$LOGS/$name.pid")
done

echo '==> [5/5] 前端 :5174'
(cd "$ROOT/frontend" && nohup npm run dev > "$LOGS/frontend.out.log" 2> "$LOGS/frontend.err.log" &
  echo $! > "$LOGS/frontend.pid")

echo '==> 等待 gateway 就绪'
for i in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8080/api/v1/auth/login \
    -H 'Content-Type: application/json' -d '{}' || true)"
  [ "$code" = "400" ] || [ "$code" = "401" ] || [ "$code" = "422" ] && break
  sleep 2
done

echo ''
echo 'AgentFlow 已启动：http://127.0.0.1:5174  (admin@agentflow.dev / agentflow123)'
