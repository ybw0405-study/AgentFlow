"""P3-E2E：human_gate 挂起 → 待办 → 裁决 → 续跑分流。

不依赖外网；审批人按 ROLE 展开（ADMIN 及以上 + 平台管理员）。
"""

from __future__ import annotations

import time
import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal


def _gate_dsl() -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {
            "question": {"type": "string", "required": True},
            "mid": {"type": "string"},
            "answer": {"type": "string"},
        },
        "defaults": {"timeoutMs": 15000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "prep",
                "type": "transform",
                "config": {
                    "mappings": [
                        {"target": "vars.mid", "template": "{{ vars.question }}-prepared"}
                    ]
                },
            },
            {
                "key": "gate",
                "type": "human_gate",
                "config": {
                    "approvers": {"type": "ROLE", "role": "ADMIN"},
                    "promptTemplate": "确认要发布吗：{{ vars.mid }}",
                    "timeoutMs": 60000,
                    "onTimeout": {"action": "REJECT"},
                },
            },
            {
                "key": "ok",
                "type": "transform",
                "config": {
                    "mappings": [{"target": "vars.answer", "template": "approved:{{ vars.mid }}"}]
                },
            },
            {
                "key": "no",
                "type": "transform",
                "config": {
                    "mappings": [{"target": "vars.answer", "template": "rejected:{{ vars.mid }}"}]
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ vars.answer }}"}},
            },
        ],
        "edges": [
            {"from": "start", "to": "prep"},
            {"from": "prep", "to": "gate"},
            {"from": "gate", "to": "ok", "branch": "approved"},
            {"from": "gate", "to": "no", "branch": "rejected"},
            {"from": "ok", "to": "end"},
            {"from": "no", "to": "end"},
        ],
    }


def _publish(client, editor_token, admin_token, dsl: dict) -> str:
    wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                     json={"name": f"e2e-gate-{uuid.uuid4().hex[:8]}"})
    assert wf.status_code == 200, wf.text
    wf_id = wf.json()["data"]["id"]
    saved = client.put(f"/api/v1/workflows/{wf_id}/draft", json=dsl,
                       headers=headers(editor_token, SPACE_A))
    assert saved.status_code == 200, saved.text
    version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                          headers=headers(editor_token, SPACE_A))
    assert version.status_code == 200, version.text
    version_id = version.json()["data"]["id"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish", json={"versionId": version_id},
                       headers=headers(admin_token, SPACE_A)).status_code == 200
    return wf_id


def _wait_status(client, token, execution_id: str, expected: str, timeout_s: float = 30.0) -> dict:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        resp = client.get(f"/api/v1/executions/{execution_id}", headers=headers(token, SPACE_A))
        assert resp.status_code == 200, resp.text
        execution = resp.json()["data"]["execution"]
        if execution["status"] == expected:
            return resp.json()["data"]
        time.sleep(0.4)
    raise AssertionError(f"execution {execution_id} never reached {expected}")


def test_gate_suspends_and_approval_resumes_approved_branch(
        client, admin_token, editor_token):
    wf_id = _publish(client, editor_token, admin_token, _gate_dsl())

    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "上线"}},
                            headers=headers(editor_token, SPACE_A))
    assert triggered.status_code == 200, triggered.text
    execution_id = triggered.json()["data"]["executionId"]

    detail = _wait_status(client, editor_token, execution_id, "SUSPENDED")
    gate_node = next(n for n in detail["nodes"] if n["nodeKey"] == "gate")
    assert gate_node["status"] == "SUSPENDED"

    inbox = client.get("/api/v1/gates", headers=headers(admin_token, SPACE_A))
    assert inbox.status_code == 200, inbox.text
    task = next(t for t in inbox.json()["data"] if t["executionId"] == execution_id)
    assert task["prompt"].startswith("确认要发布吗")
    assert task["nodeKey"] == "gate"
    assert task["executionStatus"] == "SUSPENDED"
    assert task["approvers"], "审批人快照不能为空"

    resolved = client.post(
        f"/api/v1/executions/{execution_id}/gates/gate/resolve",
        json={"decision": "APPROVED", "comment": "同意发布"},
        headers=headers(admin_token, SPACE_A))
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["data"]["branch"] == "approved"

    final = wait_terminal(client, editor_token, SPACE_A, execution_id)
    assert final["execution"]["status"] == "SUCCEEDED"

    events = client.get(f"/api/v1/executions/{execution_id}/events",
                         headers=headers(editor_token, SPACE_A)).json()["data"]["events"]
    completed = next(e for e in events
                     if e["eventType"] == "NodeCompleted" and e["nodeKey"] == "gate")
    assert completed["payload"]["output"]["branch"] == "approved"
    gate_resolved = next(e for e in events if e["eventType"] == "GateResolved")
    assert gate_resolved["payload"]["decision"] == "APPROVED"
    assert gate_resolved["payload"]["comment"] == "同意发布"

    gates = client.get(f"/api/v1/executions/{execution_id}/gates",
                       headers=headers(admin_token, SPACE_A)).json()["data"]
    assert gates[0]["status"] == "APPROVED"
    assert gates[0]["comment"] == "同意发布"


def test_gate_rejection_takes_rejected_branch(client, admin_token, editor_token):
    wf_id = _publish(client, editor_token, admin_token, _gate_dsl())
    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "回滚"}},
                            headers=headers(editor_token, SPACE_A))
    execution_id = triggered.json()["data"]["executionId"]
    _wait_status(client, editor_token, execution_id, "SUSPENDED")

    resolved = client.post(
        f"/api/v1/executions/{execution_id}/gates/gate/resolve",
        json={"decision": "REJECTED", "comment": "风险太高"},
        headers=headers(admin_token, SPACE_A))
    assert resolved.status_code == 200, resolved.text
    assert resolved.json()["data"]["branch"] == "rejected"

    final = wait_terminal(client, editor_token, SPACE_A, execution_id)
    assert final["execution"]["status"] == "SUCCEEDED"
    events = client.get(f"/api/v1/executions/{execution_id}/events",
                         headers=headers(editor_token, SPACE_A)).json()["data"]["events"]
    terminal_event = [e for e in events if e["eventType"] == "ExecutionCompleted"][-1]
    assert terminal_event["payload"]["result"]["answer"] == "rejected:回滚-prepared"
    skipped = [e["nodeKey"] for e in events if e["eventType"] == "NodeSkipped"]
    assert "ok" in skipped, "未命中分支必须显式 SKIPPED"


def test_non_approver_cannot_resolve(client, admin_token, editor_token, viewer_token):
    wf_id = _publish(client, editor_token, admin_token, _gate_dsl())
    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "权限"}},
                            headers=headers(editor_token, SPACE_A))
    execution_id = triggered.json()["data"]["executionId"]
    _wait_status(client, editor_token, execution_id, "SUSPENDED")

    forbidden = client.post(
        f"/api/v1/executions/{execution_id}/gates/gate/resolve",
        json={"decision": "APPROVED"},
        headers=headers(viewer_token, SPACE_A))
    assert forbidden.status_code == 403, forbidden.text

    # 跨空间不可见：B 空间成员看不到这条待办（404 语义）
    from .conftest import SPACE_B, BETA_EMAIL, login

    beta = login(client, BETA_EMAIL)
    foreign = client.post(f"/api/v1/executions/{execution_id}/gates/gate/resolve",
                          json={"decision": "APPROVED"}, headers=headers(beta, SPACE_B))
    assert foreign.status_code in (403, 404), foreign.text

    # 清理：让挂起的执行被驳回，避免留下永久待办
    client.post(f"/api/v1/executions/{execution_id}/gates/gate/resolve",
                json={"decision": "REJECTED", "comment": "cleanup"},
                headers=headers(admin_token, SPACE_A))


def test_resolve_after_resolution_is_idempotent(client, admin_token, editor_token):
    wf_id = _publish(client, editor_token, admin_token, _gate_dsl())
    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "幂等"}},
                            headers=headers(editor_token, SPACE_A))
    execution_id = triggered.json()["data"]["executionId"]
    _wait_status(client, editor_token, execution_id, "SUSPENDED")

    first = client.post(f"/api/v1/executions/{execution_id}/gates/gate/resolve",
                        json={"decision": "APPROVED"},
                        headers=headers(admin_token, SPACE_A))
    assert first.status_code == 200, first.text
    wait_terminal(client, editor_token, SPACE_A, execution_id)

    again = client.post(f"/api/v1/executions/{execution_id}/gates/gate/resolve",
                        json={"decision": "REJECTED"},
                        headers=headers(admin_token, SPACE_A))
    # 已裁决：要么幂等返回，要么明确的状态冲突，不能默默改分支
    assert again.status_code in (200, 409), again.text
    events = client.get(f"/api/v1/executions/{execution_id}/events",
                         headers=headers(editor_token, SPACE_A)).json()["data"]["events"]
    assert len([e for e in events if e["eventType"] == "GateResolved"]) == 1
