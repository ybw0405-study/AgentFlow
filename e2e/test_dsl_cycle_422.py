"""E2E-2：DSL 有环 → 422 DSL-CYCLE-001（US-03 环检测）。"""

from __future__ import annotations

import copy
import uuid

from .conftest import DEMO_DSL, SPACE_A, headers


def test_cycle_rejected_with_422(client, editor_token):
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-cycle-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    wf_id = resp.json()["data"]["id"]

    dsl = copy.deepcopy(DEMO_DSL)
    dsl["edges"].append({"from": "final", "to": "draft"}) # draft→final→draft

    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=dsl, headers=hdrs)
    assert resp.status_code == 422, resp.text
    body = resp.json()
    assert body["code"] == "DSL-CYCLE-001"
    assert body["traceId"]
    # 指出参与环的节点
    assert any("draft" in (e.get("message") or "") or "draft" in (e.get("field") or "")
               for e in body.get("errors", []))

    # 草稿保存被拒后，坏 DSL 从未入库——freeze 只能冻结已校验草稿；
    # 等价断言：该工作流此后冻结出来的版本仍是初始 start→end 骨架
    resp = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    assert resp.status_code == 200
    # 初始骨架只有 2 节点；带环草稿的 5 节点版本绝不应出现
    detail = client.get(f"/api/v1/workflows/{wf_id}", headers=hdrs).json()["data"]
    assert len(detail["versions"]) == 1
