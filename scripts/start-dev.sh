#!/usr/bin/env bash
# AgentFlow 一键启动（WSL2 / Linux）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$ROOT/logs"
mkdir -p "$LOGS"

echo '==> [1/5] 基础设施'
docker compose -f "$ROOT/ops/docker-compose.infra.yml" up -d

echo '==> [2/5] 等待 MySQL / RocketMQ 就绪、迁移与授权'
for c in agentflow-mysql agentflow-rmq-broker; do
  for i in $(seq 1 60); do
    state="$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo pending)"
    [ "$state" = "healthy" ] && break
    sleep 3
  done
  [ "$state" = "healthy" ] || { echo "$c 未在 3 分钟内 healthy" >&2; exit 1; }
done
docker exec -i agentflow-mysql mysql --default-character-set=utf8mb4 -uroot -p"${AF_MYSQL_ROOT_PASSWORD:-root}" agentflow < "$ROOT/scripts/seed-demo.sql" || true

# 全容器模式下 jar 由 Dockerfile 的 java-build stage 打包，宿主机 mvn 产物根本不被使用；
# 移入开发模式分支，避免只装了 Docker 的机器因缺 mvn 直接退出（P0-4 修复）。
if [ "${AF_CONTAINERS:-0}" != "1" ]; then
  echo '==> [3/5] 构建 Java 服务'
  (cd "$ROOT" && mvn -q -DskipTests -Dcheckstyle.skip package)
fi

export AF_INTERNAL_TOKEN="${AF_INTERNAL_TOKEN:-dev-internal-token}"
export AF_JWT_SECRET="${AF_JWT_SECRET:-dev-only-secret-key-0123456789abcdef}"
# 本地走明文 HTTP，refresh Cookie 不能带 Secure，否则浏览器不回传
export AF_COOKIE_SECURE="${AF_COOKIE_SECURE:-false}"
# 本地开发才下发演示账号方便登录页一键填充；生产默认 false
export AF_DEMO_ACCOUNTS="${AF_DEMO_ACCOUNTS:-true}"
# 本地全量 E2E 一轮会触发数十次执行、十几次登录；产品默认仍是 20/10 次每 60s
export AF_RATE_LIMIT_TRIGGER_MAX="${AF_RATE_LIMIT_TRIGGER_MAX:-300}"
export AF_RATE_LIMIT_LOGIN_MAX="${AF_RATE_LIMIT_LOGIN_MAX:-300}"
# compose 已经把 RocketMQ 起起来了，本地默认走真实审计链路（workflow → MQ → audit）。
# 关着的话审计只走直写、不再经 MQ 扇出；生产必须开启。
export AF_MQ_ENABLED="${AF_MQ_ENABLED:-true}"
export AF_ROCKETMQ_NAMESRV="${AF_ROCKETMQ_NAMESRV:-127.0.0.1:9876}"
# 本地开发默认开启 LLM 逐 token 流式（打字机效果）；引擎侧仍保守默认 false（opt-in），
# 生产部署不经此脚本、行为不变。显式设 AF_LLM_STREAM=false 可关闭。
export AF_LLM_STREAM="${AF_LLM_STREAM:-true}"

# 真实模型（可选）。显式设置 AF_LLM_* 时直接使用；未设置而其厂商密钥已在环境里时按该厂商派生，
# 免得每次手工导出。密钥只留在进程环境，不写入仓库与磁盘；两者都没有时引用真实模型的
# 节点会返回 LLM-NOT_CONFIGURED-001，需显式注入 AF_LLM_*。
if [ -z "${AF_LLM_API_KEY:-}" ] && [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  export AF_LLM_BASE_URL="${AF_LLM_BASE_URL:-https://api.deepseek.com/v1}"
  export AF_LLM_API_KEY="$DEEPSEEK_API_KEY"
  export AF_LLM_MODEL="${AF_LLM_MODEL:-deepseek-chat}"
fi

# 全容器模式下 topic 由 compose 的 mq-init 服务预建，宿主机无需重复（P0-4 修复）
if [ "${AF_CONTAINERS:-0}" != "1" ]; then
echo '==> [3.5/5] 预建 MQ topic（避免消费端等首条消息才拿到路由）'
# autoCreateTopicEnable 只在「第一次发送」时建 topic；消费端在消息到来前查路由会拿到 17。
# 两个坑：
#  1) namesrv 是独立容器，broker 容器内的 127.0.0.1:9876 够不到它，必须走 docker 网络主机名；
#  2) mqadmin 命令失败时退出码仍是 0，不能信退出码——用 topicRoute 输出里有无 brokerDatas 判定。
# HOME=/tmp：命名卷属主问题让 rocketmq 用户建不了 ~/logs/rocketmqlogs，工具日志改写到 /tmp。
# broker 的 TCP 健康检查通过 ≠ 已完成首次 namesrv 注册（注册周期 ~30s），预建带重试窗口。
for t in af-audit-event af-gate-decision; do
  ok=false
  for i in $(seq 1 18); do
    docker exec -e HOME=/tmp agentflow-rmq-broker sh -c \
      "sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c DefaultCluster -t $t" >/dev/null 2>&1
    if docker exec -e HOME=/tmp agentflow-rmq-broker sh -c \
      "sh mqadmin topicRoute -t $t -n rocketmq-namesrv:9876" 2>/dev/null | grep -q brokerDatas; then
      ok=true; break
    fi
    sleep 5
  done
  if [ "$ok" != true ]; then
    echo "预建 topic $t 失败：90s 内 namesrv 始终查不到路由（docker logs agentflow-rmq-broker）" >&2
    exit 1
  fi
done
fi

if [ "${AF_CONTAINERS:-0}" = "1" ]; then
  echo '==> [4/5] AF_CONTAINERS=1：全栈 compose 起应用层'
  docker compose -f "$ROOT/ops/docker-compose.infra.yml" \
                 -f "$ROOT/ops/docker-compose.app.yml" up -d
  echo '==> 网关 http://127.0.0.1:8080 / 前端 http://127.0.0.1:8081'
  exit 0
fi

echo '==> [4/5] engine-api :8000'
PY="$ROOT/engine-api/.venv/Scripts/python.exe"
[ -x "$PY" ] || PY="$ROOT/engine-api/.venv/bin/python"
(cd "$ROOT/engine-api" && nohup "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 \
  > "$LOGS/engine.out.log" 2> "$LOGS/engine.err.log" & echo $! > "$LOGS/engine.pid")

# jar 名带版本号，按目录解析，免得每次升版本都要改脚本
resolve_jar() {
  local name="$1" found
  found="$(ls -t "$ROOT/$name/target/$name-"*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
  [ -n "$found" ] || { echo "未找到 $name 的可执行 jar；先跑 mvn package" >&2; return 1; }
  echo "$found"
}

for svc in workflow-service:8084 agent-gateway:8080 audit-service:8086 connector-service:8085; do
  name="${svc%%:*}"
  jar="$(resolve_jar "$name")" || exit 1
  echo "==> [4/5] $name"
  (cd "$ROOT" && nohup java -jar "$jar" \
    > "$LOGS/$name.out.log" 2> "$LOGS/$name.err.log" & echo $! > "$LOGS/$name.pid")
done

echo '==> [5/5] 前端 :5174'
(cd "$ROOT/frontend" && nohup npm run dev > "$LOGS/frontend.out.log" 2> "$LOGS/frontend.err.log" &
  echo $! > "$LOGS/frontend.pid")

echo '==> 等待 gateway 就绪'
for i in $(seq 1 60); do
  # /health 会连带探 Redis：依赖不可用时返回 503，正好当作 readiness 用
  code="$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/health || true)"
  if [ "$code" = "200" ]; then
    echo "    就绪（约 $((i * 2))s）"
    break
  fi
  sleep 2
done

echo ''
echo 'AgentFlow 已启动：http://127.0.0.1:5174  (admin@agentflow.dev / agentflow123)'
