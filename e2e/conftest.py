"""E2E 装置：对运行中的全栈（gateway 入口）发起 API 级验收。

运行前置：scripts/start-dev.* 已启动全部服务并完成种子。
环境变量：AF_E2E_BASE_URL（默认 http://127.0.0.1:8080）。
"""

from __future__ import annotations

import os
import uuid

import httpx
import pytest

BASE_URL = os.environ.get("AF_E2E_BASE_URL", "http://127.0.0.1:8080")
# 本机做真实模型验收时置 AF_E2E_REAL_LLM=1；CI 未注入密钥时仍走离线测试桩。
REAL_LLM = os.environ.get("AF_E2E_REAL_LLM", "") == "1"
REAL_LLM_MODEL = os.environ.get("AF_LLM_MODEL", "deepseek-chat")
# 引擎内部端点（/internal/**）不经网关：探针类断言直接打 8000，带内部令牌
ENGINE_URL = os.environ.get("AF_E2E_ENGINE_URL", "http://127.0.0.1:8000")
INTERNAL_TOKEN = os.environ.get("AF_INTERNAL_TOKEN", "dev-internal-token") # noqa: S105


ADMIN_EMAIL = "admin@agentflow.dev"
EDITOR_EMAIL = "editor@agentflow.dev"
VIEWER_EMAIL = "viewer@agentflow.dev"
BETA_EMAIL = "beta@agentflow.dev"
PASSWORD = "agentflow123"

SPACE_A = "t-space-a"
SPACE_B = "t-space-beta"

DEMO_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True, "pii": True}},
    "defaults": {"timeoutMs": 60000, "maxParallelism": 4},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "draft", "type": "llm", "config": {
            "provider": "openai-compatible",
            "model": REAL_LLM_MODEL,
            "promptTemplate": "请根据问题起草答案要点：{{ vars.question }}",
            "maxTokens": 512}},
        {"key": "enrich", "type": "transform", "config": {
            "mappings": [{"target": "vars.enrich_text",
                          "template": "检索上下文：{{ vars.question }}"}]}},
        {"key": "final", "type": "llm", "config": {
            "provider": "openai-compatible",
            "model": REAL_LLM_MODEL,
            "promptTemplate": "要点：{{ nodes.draft.output.text }}\n"
                              "检索：{{ nodes.enrich.output.enrich_text }}\n"
                              "问题：{{ vars.question }}",
            "maxTokens": 512}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ nodes.final.output.text }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "draft"},
        {"from": "start", "to": "enrich"},
        {"from": "draft", "to": "final"},
        {"from": "enrich", "to": "final"},
        {"from": "final", "to": "end"},
    ],
}


@pytest.fixture(scope="session")
def base_url() -> str:
    return BASE_URL


@pytest.fixture(scope="session")
def client(base_url) -> httpx.Client:
    with httpx.Client(base_url=base_url, timeout=20.0) as c:
        yield c


def login(client: httpx.Client, email: str) -> str:
    resp = client.post("/api/v1/auth/login", json={"email": email, "password": PASSWORD})
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]["accessToken"]


def headers(token: str, space: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}", "X-Space-Id": space}


@pytest.fixture(scope="session")
def admin_token(client) -> str:
    return login(client, ADMIN_EMAIL)


@pytest.fixture(scope="session")
def editor_token(client) -> str:
    return login(client, EDITOR_EMAIL)


@pytest.fixture(scope="session")
def viewer_token(client) -> str:
    return login(client, VIEWER_EMAIL)


@pytest.fixture(scope="session")
def beta_token(client) -> str:
    return login(client, BETA_EMAIL)


def wait_terminal(client: httpx.Client, token: str, space: str,
                  execution_id: str, timeout_s: float = 30.0) -> dict:
    import time

    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        resp = client.get(f"/api/v1/executions/{execution_id}", headers=headers(token, space))
        assert resp.status_code == 200, resp.text
        execution = resp.json()["data"]["execution"]
        if execution["status"] in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"):
            return resp.json()["data"]
        time.sleep(0.5)
    raise AssertionError(f"execution {execution_id} not terminal within {timeout_s}s")


def new_idem() -> str:
    return uuid.uuid4().hex


def engine_mq_status() -> dict | None:
    """引擎的 MQ 消费端状态；引擎不可达或未开启时返回 None。"""
    try:
        resp = httpx.get(f"{ENGINE_URL}/internal/mq/status",
                         headers={"X-Internal-Token": INTERNAL_TOKEN}, timeout=5.0)
    except httpx.HTTPError:
        return None
    return resp.json() if resp.status_code == 200 else None
