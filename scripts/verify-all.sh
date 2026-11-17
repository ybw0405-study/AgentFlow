#!/usr/bin/env bash
# AgentFlow 本地全量门禁（对齐 CI）。
# 每步加超时保护：vitest worker 在部分环境下不自动退出，避免单步挂起拖死整条门禁。
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAILED=()
STEP_TIMEOUT="${STEP_TIMEOUT:-300}"   # 单步超时秒数，可覆盖

run_step() {
  local label="$1"; shift
  echo "==> $label"
  timeout "$STEP_TIMEOUT" "$@" || {
    local rc=$?
    if [ "$rc" -eq 124 ]; then
      echo "    TIMEOUT after ${STEP_TIMEOUT}s: $label"
    fi
    FAILED+=("$label")
  }
}

SKIP_JAVA=0; SKIP_PYTHON=0; SKIP_FRONTEND=0
for arg in "$@"; do
  case "$arg" in
    --skip-java) SKIP_JAVA=1 ;;
    --skip-python) SKIP_PYTHON=1 ;;
    --skip-frontend) SKIP_FRONTEND=1 ;;
  esac
done

if [ "$SKIP_JAVA" = 0 ]; then
  # 跳过 repackage：本地服务运行时 jar 被锁，Windows 下 spring-boot:repackage 会重命名失败。
  # 保留 verify 的测试 + checkstyle + ArchUnit 全部校验；CI 无运行服务，仍用完整 mvn verify。
  echo '==> java: mvn verify (skip repackage)'
  (cd "$ROOT" && timeout "$STEP_TIMEOUT" mvn -B -ntp verify -Dspring-boot.repackage.skip=true) || FAILED+=('java')
fi

if [ "$SKIP_PYTHON" = 0 ]; then
  PY="$ROOT/engine-api/.venv/Scripts/python.exe"
  [ -x "$PY" ] || PY="$ROOT/engine-api/.venv/bin/python"
  echo '==> python: ruff + pytest'
  (cd "$ROOT/engine-api" && "$PY" -m ruff check app tests) || FAILED+=('python-ruff')
  (cd "$ROOT/engine-api" && "$PY" -m ruff format --check app tests) || FAILED+=('python-format')
  (cd "$ROOT/engine-api" && timeout "$STEP_TIMEOUT" "$PY" -m pytest -q) || FAILED+=('python-pytest')
fi

if [ "$SKIP_FRONTEND" = 0 ]; then
  echo '==> frontend: typecheck + lint + test + build'
  (cd "$ROOT/frontend" && [ -d node_modules ] || timeout "$STEP_TIMEOUT" npm ci) || FAILED+=('frontend-ci')
  (cd "$ROOT/frontend" && timeout "$STEP_TIMEOUT" npm run typecheck) || FAILED+=('frontend-typecheck')
  (cd "$ROOT/frontend" && timeout "$STEP_TIMEOUT" npm run lint) || FAILED+=('frontend-lint')
  # vitest 完成后 worker 可能残留，用 timeout 兜底；vitest 自身仍以退出码判定成败
  (cd "$ROOT/frontend" && timeout "$STEP_TIMEOUT" npm run test) || {
    rc=$?
    [ "$rc" -eq 124 ] && echo '    TIMEOUT after vitest (worker residue; results above are authoritative)'
    FAILED+=('frontend-test')
  }
  (cd "$ROOT/frontend" && timeout "$STEP_TIMEOUT" npm run build) || FAILED+=('frontend-build')
fi

echo ''
if [ "${#FAILED[@]}" -gt 0 ]; then
  echo "FAILED: ${FAILED[*]}"
  exit 1
fi
echo 'ALL GATES GREEN'
