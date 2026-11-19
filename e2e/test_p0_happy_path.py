"""E2E-1（P0 验收主链路）：创建 → 草稿 → 冻结 → 发布 → 执行 → 逐节点输出 + token。

含 PII 变量入参：节点 I/O 中必须只见 [PHONE] 不见原文（US-05 双层脱敏）。
"""

from __future__ import annotations

import copy
import uuid

from .conftest import DEMO_DSL, SPACE_A, headers, new_idem, wait_terminal


def test_full_lifecycle(client, editor_token, admin_token):
    hdrs = headers(editor_token, SPACE_A)

    # 1. 创建
    resp = client.post("/api/v1/workflows", json={"name": f"e2e-demo-{uuid.uuid4().hex[:8]}"},
                       headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]

    # 2. 保存草稿（服务端校验）
    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=DEMO_DSL, headers=hdrs)
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["checksum"]

    # 3. 冻结 + 发布
    resp = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    assert resp.status_code == 200, resp.text
    version_id = resp.json()["data"]["id"]
    assert resp.json()["data"]["versionNo"] == 1

    resp = client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version_id},
                       headers=headers(admin_token, SPACE_A))  # 发布须 ADMIN
    assert resp.status_code == 200, resp.text

    # 4. 触发（变量含手机号，验证双层脱敏）
    question = "我的手机 13812345678 无法开机，AgentFlow 能做什么？"
    resp = client.post(f"/api/v1/workflows/{wf_id}/executions",
                       json={"idempotencyKey": new_idem(),
                             "variables": {"question": question},
                             "policy": {"tokenBudget": 8000, "maxSteps": 30,
                                        "deadlineMs": 60000}},
                       headers=hdrs)
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    execution_id = data["executionId"]
    assert data["status"] in ("PENDING", "RUNNING")
    assert data["streamUrl"].endswith("/events/stream")

    # 5. 执行到终态
    detail = wait_terminal(client, editor_token, SPACE_A, execution_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail
    assert detail["execution"]["tokenUsed"] > 0
    assert detail["execution"]["stepsUsed"] == 5

    # 6. 节点树：5 个节点全 SUCCEEDED，顺序拓扑
    nodes = {n["nodeKey"]: n for n in detail["nodes"]}
    assert set(nodes) == {"start", "draft", "enrich", "final", "end"}
    assert all(n["status"] == "SUCCEEDED" for n in nodes.values())

    # 6.1 详情接口要带回最终产出（end 的 resultMapping），否则页面只能让人逐个点节点看
    assert detail["result"], detail
    assert "answer" in detail["result"]

    # 7. 事件流可追溯
    resp = client.get(f"/api/v1/executions/{execution_id}/events", headers=hdrs)
    assert resp.status_code == 200
    events = resp.json()["data"]["events"]
    types = [e["eventType"] for e in events]
    assert types[0] == "ExecutionCreated"
    assert "ExecutionCompleted" in types
    assert types.count("NodeCompleted") == 5

    # 8. 节点 I/O：原文脱敏可见（[PHONE]，无原文手机号）
    resp = client.get(f"/api/v1/executions/{execution_id}/nodes/draft/io", headers=hdrs)
    assert resp.status_code == 200
    io = resp.json()["data"]
    prompt = io["in"]["payload"]["prompt"]
    assert "[PHONE]" in prompt
    assert "13812345678" not in prompt

    # 9. 刷新后执行记录仍在（持久化）
    resp = client.get("/api/v1/executions", headers=hdrs)
    assert resp.status_code == 200
    ids = [e["id"] for e in resp.json()["data"]["items"]]
    assert execution_id in ids
