"""E2E（P1）：subworkflow 节点经 gateway 内部端点解析子工作流 DSL 并回传结果。

子工作流使用种子数据 wf-demo（start→draft→enrich→final→end），
其 end 输出 {"answer": <text>}。父工作流通过 outputMapping 映射 answer。
"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal

PARENT_DSL = {
    "dslVersion": "1.0",
    "variables": {
        "question": {"type": "string", "required": True},
        "answer": {"type": "string", "required": False},
    },
    "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "sub", "type": "subworkflow", "config": {
            "definitionId": "wf-demo",
            "versionPolicy": "LATEST_PUBLISHED",
            "inputMapping": {"question": "{{ vars.question }}"},
            "outputMapping": {"answer": "{{ vars.answer }}"},
        }},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"final": "{{ vars.answer }}"}}},
    ],
    "edges": [{"from": "start", "to": "sub"}, {"from": "sub", "to": "end"}],
}


def test_subworkflow_e2e(client, editor_token, admin_token):
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-subwf-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]

    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=PARENT_DSL,
                      headers=hdrs).status_code == 200
    ver = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs).json()["data"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": ver["id"]},
                       headers=headers(admin_token, SPACE_A)).status_code == 200

    resp = client.post(f"/api/v1/workflows/{wf_id}/executions", json={
        "idempotencyKey": new_idem(),
        "variables": {"question": "解释 subworkflow 如何工作"},
        "policy": {"tokenBudget": 8000, "maxSteps": 30, "deadlineMs": 60000},
    }, headers=hdrs)
    assert resp.status_code == 200, resp.text
    exec_id = resp.json()["data"]["executionId"]

    detail = wait_terminal(client, editor_token, SPACE_A, exec_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail
    nodes = {n["nodeKey"]: n["status"] for n in detail["nodes"]}
    assert nodes.get("sub") == "SUCCEEDED", nodes
    # 子工作流节点带前缀，事件可追溯
    assert any(k.startswith("sub#run#") for k in nodes), nodes
    # 结果：子 end 的 answer 经 outputMapping 映射，父 end 拿到非空 final
    # （执行详情 API 不暴露 result，从 ExecutionCompleted 事件读取）
    events = client.get(f"/api/v1/executions/{exec_id}/events", headers=hdrs).json()["data"]["events"]
    completed = [e for e in events if e["eventType"] == "ExecutionCompleted"]
    assert completed and completed[-1]["payload"]["result"].get("final"), nodes
