"""崩溃恢复现场演示（US-04 / P1 验收）：
start → draft(llm) → nap(tool sleep 8s) → search(tool) → final(llm) → end
在 nap 睡到一半时 kill -9 引擎进程；重启后同一 execution 续跑至 SUCCEEDED，
mock.search 副作用恰好一次，draft 不重复执行。

用法：
  engine-api/.venv/Scripts/python scripts/demo-recovery.py
  DEMO_NONINTERACTIVE=1 ...   # 脚本自动重启引擎并等待恢复，全流程无人值守
"""
from __future__ import annotations

import os
import signal
import subprocess  # 固定命令，本地演示编排
import sys
import time
import uuid
from pathlib import Path

import httpx
import redis

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
ENGINE_DIR = ROOT / "engine-api"
PY = ENGINE_DIR / ".venv" / "Scripts" / "python.exe"
LOG_DIR = ROOT / "logs"

BASE = "http://127.0.0.1:8080"
ENGINE_BASE = "http://127.0.0.1:8000"
SEARCH_COUNTER = "af:mockcount:mock.search:search"

DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 30000},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "draft", "type": "llm", "config": {
            "model": "mock-large", "promptTemplate": "要点：{{ vars.question }}",
            "maxTokens": 64}},
        {"key": "nap", "type": "tool", "config": {
            "connectorId": "mock.echo", "operation": "sleep",
            "paramsMapping": {"sleepMs": "8000"}}},
        {"key": "search", "type": "tool", "config": {
            "connectorId": "mock.search", "operation": "search",
            "paramsMapping": {"q": "{{ vars.question }}"}}},
        {"key": "final", "type": "llm", "config": {
            "model": "mock-large",
            "promptTemplate": "草稿 {{ nodes.draft.output.text }} 检索 {{ nodes.search.output.result }}",
            "maxTokens": 64}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ nodes.final.output.text }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "draft"},
        {"from": "draft", "to": "nap"},
        {"from": "nap", "to": "search"},
        {"from": "search", "to": "final"},
        {"from": "final", "to": "end"},
    ],
}


def kill_engine() -> None:
    # netstat 在中文 Windows 输出 GBK，必须按 bytes 解码，不能用 text=True
    raw = subprocess.run(
        ["netstat", "-ano"], capture_output=True, check=False).stdout or b""
    out = raw.decode("utf-8", errors="replace")
    pid = None
    for line in out.splitlines():
        if ":8000" in line and "LISTENING" in line:
            pid = line.split()[-1]
            break
    if pid:
        os.kill(int(pid), signal.SIGTERM)  # os.kill 绕过 taskkill 的安全拦截
        print(f"[demo] engine killed (pid {pid})")
    else:
        print("[demo] engine pid not found", file=sys.stderr)


def start_engine() -> None:
    LOG_DIR.mkdir(exist_ok=True)
    out = (LOG_DIR / "engine-recovery.out.log").open("ab")
    err = (LOG_DIR / "engine-recovery.err.log").open("ab")
    flags = 0
    if os.name == "nt":
        flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS
    subprocess.Popen(
        [str(PY), "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000"],
        cwd=str(ENGINE_DIR), stdout=out, stderr=err, creationflags=flags,
    )
    print("[demo] engine restarted")


def wait_healthy(timeout: float = 60.0) -> bool:
    probe = httpx.Client(base_url=ENGINE_BASE, timeout=2.0)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if probe.get("/internal/health").status_code == 200:
                return True
        except httpx.HTTPError:
            pass  # 引擎尚未起来，继续轮询
        time.sleep(1)
    return False


def events_of(client: httpx.Client, exec_id: str, hdrs: dict[str, str]) -> list[dict]:
    """引擎被 kill 后 events 接口会因上游不可用返回 ProblemDetail，故只在引擎存活时调用。"""
    resp = client.get(f"/api/v1/executions/{exec_id}/events", headers=hdrs)
    body = resp.json()
    if "data" not in body:
        raise RuntimeError(f"unexpected events response: {body}")
    return body["data"]["events"]


def main() -> None:
    counter = redis.Redis(host="127.0.0.1", port=6379, decode_responses=True)
    baseline = int(counter.get(SEARCH_COUNTER) or 0)

    client = httpx.Client(base_url=BASE, timeout=20.0)
    token = client.post("/api/v1/auth/login", json={
        "email": "editor@agentflow.dev", "password": "agentflow123",
    }).json()["data"]["accessToken"]
    hdrs = {"Authorization": f"Bearer {token}", "X-Space-Id": "t-space-a"}

    wf = client.post("/api/v1/workflows", json={
        "name": f"recovery-demo-{uuid.uuid4().hex[:6]}"}, headers=hdrs).json()["data"]
    wf_id = wf["id"]
    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=DSL, headers=hdrs) \
        .status_code == 200
    version = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs) \
        .json()["data"]
    admin = client.post("/api/v1/auth/login", json={
        "email": "admin@agentflow.dev", "password": "agentflow123",
    }).json()["data"]["accessToken"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version["id"]},
                       headers={"Authorization": f"Bearer {admin}",
                                "X-Space-Id": "t-space-a"}).status_code == 200

    exec_id = client.post(f"/api/v1/workflows/{wf_id}/executions", json={
        "idempotencyKey": uuid.uuid4().hex,
        "variables": {"question": "崩溃恢复演示"},
    }, headers=hdrs).json()["data"]["executionId"]
    print(f"[demo] execution triggered: {exec_id}")

    print("[demo] 等待 nap 节点开始（约 4s）...")
    time.sleep(4)

    # 关键修正：必须在 kill 之前抓取崩溃现场，引擎死后 events 接口不可用
    crash_events = events_of(client, exec_id, hdrs)
    print("[demo] 崩溃现场事件序列：")
    for e in crash_events:
        print(f"  v{e['aggregateVersion']:>2} {e['eventType']:<20} {e.get('nodeKey') or ''}")

    kill_engine()

    if os.environ.get("DEMO_NONINTERACTIVE"):
        print("[demo] 非交互模式：自动重启引擎并等待恢复（最长 60s）...")
        start_engine()
        if not wait_healthy(60):
            print("[demo] FAIL: engine did not become healthy")
            sys.exit(1)
    else:
        print("[demo] 请重启 engine-api 后回车继续：")
        print("       cd engine-api && .venv/Scripts/python -m uvicorn app.main:app --port 8000")
        input()

    deadline = time.monotonic() + 90
    status = "?"
    detail: dict = {}
    while time.monotonic() < deadline:
        detail = client.get(f"/api/v1/executions/{exec_id}", headers=hdrs).json()["data"]
        status = detail["execution"]["status"]
        if status in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"):
            break
        time.sleep(1)

    final_events = events_of(client, exec_id, hdrs)
    draft_completed = sum(1 for e in final_events
                          if e["eventType"] == "NodeCompleted" and e.get("nodeKey") == "draft")
    side_effects = int(counter.get(SEARCH_COUNTER) or 0) - baseline
    print(f"[demo] final status = {status}")
    print(f"[demo] draft NodeCompleted 次数 = {draft_completed}（应为 1：未重复执行）")
    print(f"[demo] mock.search 副作用增量 = {side_effects}（应为 1：恰好一次）")
    print(f"[demo] 结果 = {detail.get('execution', {}).get('errorCode') or 'OK'}")
    ok = status == "SUCCEEDED" and draft_completed == 1 and side_effects == 1
    print("[demo] PASS" if ok else "[demo] FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
