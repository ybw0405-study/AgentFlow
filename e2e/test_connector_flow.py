"""P2-E2E：连接器生命周期 + 沙箱拦截（引擎 → 网关 → connector-service 全链路）。

不依赖外网：连接器指向 127.0.0.1，沙箱默认禁止环回/明文 http，因此工具节点必然以
CONN-SANDBOX-001 失败 —— 这恰好证明「凭据与出网都发生在 connector-service 侧」，
引擎拿不到绕过沙箱的能力。
"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal


def _tool_dsl(connector_id: str) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {},
        "defaults": {"timeoutMs": 15000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {"key": "call", "type": "tool", "config": {
                "connectorId": connector_id, "operation": "ping", "paramsMapping": {}}},
            {"key": "end", "type": "end", "config": {"resultMapping": {"result": "done"}}},
        ],
        "edges": [
            {"from": "start", "to": "call"},
            {"from": "call", "to": "end"},
        ],
    }


def test_connector_crud_authorize_and_sandbox_denial(client, admin_token, editor_token):
    hdrs = headers(admin_token, SPACE_A)
    name = f"e2e-conn-{uuid.uuid4().hex[:8]}"

    created = client.post("/api/v1/connectors", headers=hdrs, json={
        "name": name,
        "type": "HTTP",
        "authType": "API_KEY",
        "config": {
            "baseUrl": "http://127.0.0.1:9",
            "allowedHosts": ["127.0.0.1"],
            "operations": {"ping": {"method": "GET", "path": "/ping"}},
        },
    })
    assert created.status_code == 200, created.text
    connector = created.json()["data"]
    connector_id = connector["id"]
    assert connector["hasCredential"] is False
    assert connector["inherited"] is False

    # 授权：密文入库，响应不回显密钥
    resp = client.put(f"/api/v1/connectors/{connector_id}/authorize",
                      json={"secret": "e2e-secret"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    assert "e2e-secret" not in resp.text

    listing = client.get("/api/v1/connectors", headers=hdrs)
    assert listing.status_code == 200, listing.text
    row = next(r for r in listing.json()["data"] if r["id"] == connector_id)
    assert row["hasCredential"] is True
    assert "e2e-secret" not in listing.text

    # 建一个引用该连接器的工作流并执行
    wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                     json={"name": f"e2e-conn-wf-{uuid.uuid4().hex[:6]}"})
    assert wf.status_code == 200, wf.text
    wf_id = wf.json()["data"]["id"]
    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=_tool_dsl(connector_id),
                      headers=headers(editor_token, SPACE_A)).status_code == 200
    version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                          headers=headers(editor_token, SPACE_A))
    assert version.status_code == 200, version.text
    version_id = version.json()["data"]["id"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version_id},
                       headers=hdrs).status_code == 200

    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(), "variables": {},
                                  "policy": {"tokenBudget": 2000, "maxSteps": 10,
                                             "deadlineMs": 30000}},
                            headers=headers(editor_token, SPACE_A))
    assert triggered.status_code == 200, triggered.text
    execution_id = triggered.json()["data"]["executionId"]

    detail = wait_terminal(client, editor_token, SPACE_A, execution_id, timeout_s=40)
    assert detail["execution"]["status"] == "FAILED", detail
    call_node = next(n for n in detail["nodes"] if n["nodeKey"] == "call")
    assert call_node["errorCode"] == "CONN-SANDBOX-001", call_node

    # 清理
    assert client.delete(f"/api/v1/connectors/{connector_id}",
                         headers=hdrs).status_code == 200


def test_connector_requires_admin(client, editor_token):
    resp = client.post("/api/v1/connectors", headers=headers(editor_token, SPACE_A),
                       json={"name": "editor-attempt", "type": "HTTP", "authType": "NONE",
                             "config": {"baseUrl": "https://api.example.com",
                                        "allowedHosts": ["api.example.com"]}})
    assert resp.status_code == 403, resp.text


def test_connector_validation_rejects_missing_allowlist(client, admin_token):
    resp = client.post("/api/v1/connectors", headers=headers(admin_token, SPACE_A),
                       json={"name": "no-allowlist", "type": "HTTP", "authType": "NONE",
                             "config": {"baseUrl": "https://api.example.com"}})
    assert resp.status_code == 400, resp.text
