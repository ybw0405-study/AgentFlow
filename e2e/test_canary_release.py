"""E2E（P1）：版本灰度发布与一键回滚。

场景：
1. 发布 v1（marker=v1）作为 current；再生成 v2（marker=v2）但不发布；
2. 配置灰度 primary=v1 / canary=v2 / percent=100 → 新执行全部落到 v2；
3. 一键回滚（percent→0）→ 新执行回到 v1；
4. 非管理员（editor）调灰度接口返回 403；
5. 灰度决策写审计（WF_CANARY_ROUTED）。
"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal


def _dsl(marker: str) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {
            "marker": {"type": "string", "required": False},
        },
        "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {"key": "tag", "type": "transform", "config": {
                "mappings": [{"target": "vars.marker", "template": marker}]}},
            {"key": "end", "type": "end", "config": {
                "resultMapping": {"marker": "{{ vars.marker }}"}}},
        ],
        "edges": [
            {"from": "start", "to": "tag"},
            {"from": "tag", "to": "end"},
        ],
    }


def _run_marker(client, editor_token, wf_id: str) -> str:
    resp = client.post(f"/api/v1/workflows/{wf_id}/executions", json={
        "idempotencyKey": new_idem(),
        "variables": {},
    }, headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    exec_id = resp.json()["data"]["executionId"]
    detail = wait_terminal(client, editor_token, SPACE_A, exec_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail
    events = client.get(f"/api/v1/executions/{exec_id}/events",
                        headers=headers(editor_token, SPACE_A)).json()["data"]["events"]
    completed = [e for e in events if e["eventType"] == "ExecutionCompleted"]
    assert completed, events[-3:]
    return (completed[-1]["payload"].get("result") or {}).get("marker")


def test_canary_routing_and_rollback(client, editor_token, admin_token):
    ed = headers(editor_token, SPACE_A)
    ad = headers(admin_token, SPACE_A)

    # 1. 建工作流：v1 发布为 current，v2 只生成不发布
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-canary-{uuid.uuid4().hex[:8]}"}, headers=ed)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]

    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=_dsl("v1"),
                      headers=ed).status_code == 200
    v1 = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=ed).json()["data"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": v1["id"]}, headers=ad).status_code == 200

    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=_dsl("v2"),
                      headers=ed).status_code == 200
    v2 = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=ed).json()["data"]
    assert v1["id"] != v2["id"]

    # 基线：灰度未配置时走 current（v1）
    assert _run_marker(client, editor_token, wf_id) == "v1"

    # 2. 配置灰度 100% 到 canary(v2)
    resp = client.post(f"/api/v1/admin/canary/{wf_id}", json={
        "primaryVersionId": v1["id"], "canaryVersionId": v2["id"], "percent": 100,
    }, headers=ad)
    assert resp.status_code == 200, resp.text
    cfg = resp.json()["data"]
    assert cfg["percent"] == 100 and cfg["canaryVersionId"] == v2["id"], cfg

    assert _run_marker(client, editor_token, wf_id) == "v2"

    # 3. 一键回滚：percent→0，全部回 primary
    resp = client.post(f"/api/v1/admin/canary/{wf_id}/rollback", headers=ad)
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["percent"] == 0
    assert _run_marker(client, editor_token, wf_id) == "v1"

    # 4. 非管理员无权改动灰度
    resp = client.post(f"/api/v1/admin/canary/{wf_id}", json={
        "primaryVersionId": v1["id"], "canaryVersionId": v2["id"], "percent": 50,
    }, headers=ed)
    assert resp.status_code == 403, resp.text

    # 5. 非法版本（不属于该 definition）被拒
    resp = client.post(f"/api/v1/admin/canary/{wf_id}", json={
        "primaryVersionId": "not-a-version", "canaryVersionId": v2["id"], "percent": 10,
    }, headers=ad)
    assert resp.status_code == 404, resp.text

    # 6. 灰度决策已写审计（MQ 异步，轮询等待落库）
    import time
    deadline = time.monotonic() + 15
    event_types: list[str] = []
    while time.monotonic() < deadline:
        resp = client.get("/api/v1/admin/audit", headers=ad,
                          params={"limit": 100, "eventType": "WF_CANARY_ROUTED"})
        assert resp.status_code == 200, resp.text
        event_types = [i.get("eventType") for i in resp.json()["data"]["items"]]
        if event_types:
            break
        time.sleep(0.5)
    assert "WF_CANARY_ROUTED" in event_types, event_types[:20]
