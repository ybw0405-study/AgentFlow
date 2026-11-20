"""P2-E2E：执行看护 —— 卡在「运行中」的执行由控制面收口，管理端可见、可手动触发。

看护本身是后台任务（60 秒一轮），这里验的是它对外露出的两件事：
候选列表与手动巡检的口径一致，且终态执行不会被当成候选。
「引擎失联 / 派发丢失」的真实收口需要构造故障，放在 Java 单测（ExecutionReaperTest）里。
"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal

# 纯 transform 工作流：不烧模型，只用来造一条真实的终态执行。
FAST_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 30000, "maxParallelism": 2},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "echo", "type": "transform", "config": {
            "mappings": [{"target": "vars.answer",
                          "template": "已收到：{{ vars.question }}"}]}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ vars.answer }}"}}},
    ],
    "edges": [{"from": "start", "to": "echo"}, {"from": "echo", "to": "end"}],
}


def _stale(client, token: str, space: str = SPACE_A):
    return client.get("/api/v1/admin/executions/stale", headers=headers(token, space))


def test_only_platform_admin_can_inspect_the_reaper(client, editor_token, viewer_token):
    # 看护会改执行状态：普通成员连候选都不该看见
    for token in (editor_token, viewer_token):
        assert _stale(client, token).status_code == 403
        resp = client.post("/api/v1/admin/executions/sweep", headers=headers(token, SPACE_A))
        assert resp.status_code == 403, resp.text


def test_terminal_execution_is_not_a_candidate_and_sweep_is_clean(
        client, admin_token, editor_token):
    hdrs = headers(editor_token, SPACE_A)
    wf = client.post("/api/v1/workflows", headers=hdrs,
                     json={"name": f"e2e-reap-{uuid.uuid4().hex[:8]}"})
    assert wf.status_code == 200, wf.text
    wf_id = wf.json()["data"]["id"]
    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=FAST_DSL,
                      headers=hdrs).status_code == 200
    version_id = client.post(f"/api/v1/workflows/{wf_id}/versions",
                             headers=hdrs).json()["data"]["id"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version_id},
                       headers=headers(admin_token, SPACE_A)).status_code == 200

    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "看护验收"},
                                  "policy": {"tokenBudget": 1000, "maxSteps": 10,
                                             "deadlineMs": 30000}},
                            headers=hdrs)
    assert triggered.status_code == 200, triggered.text
    execution_id = triggered.json()["data"]["executionId"]
    detail = wait_terminal(client, editor_token, SPACE_A, execution_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail

    listed = _stale(client, admin_token)
    assert listed.status_code == 200, listed.text
    items = listed.json()["data"]
    assert isinstance(items, list)
    # 终态执行不在候选里——候选只装「还没收口」的执行
    assert execution_id not in {item["executionId"] for item in items}
    for item in items:
        assert item["status"] in ("PENDING", "RUNNING")
        assert item["overdueSeconds"] >= 0
        # neverStarted 决定「派发丢失」还是「超过 deadline」的文案，必须跟状态一致
        assert item["neverStarted"] == (item["status"] == "PENDING")

    report = client.post("/api/v1/admin/executions/sweep", headers=headers(admin_token, SPACE_A))
    assert report.status_code == 200, report.text
    data = report.json()["data"]
    assert set(data) == {"scanned", "reconciled", "reaped", "alive", "failed"}
    assert data["scanned"] >= 0

    # 巡检是幂等的：跑完之后这条执行仍然不在候选里，也不会被重复收口
    client.post("/api/v1/admin/executions/sweep", headers=headers(admin_token, SPACE_A))
    after = _stale(client, admin_token).json()["data"]
    assert execution_id not in {item["executionId"] for item in after}
