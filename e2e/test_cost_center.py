"""P2-E2E：成本中心 —— 节点完成事件经投影器累加到租户月度账本。"""

from __future__ import annotations

import time
import uuid

from .conftest import DEMO_DSL, SPACE_A, headers, new_idem, wait_terminal


def _usage(client, admin_token: str) -> dict:
    resp = client.get("/api/v1/admin/usage", headers=headers(admin_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


def _row(usage: dict, tenant_id: str) -> dict:
    return next(item for item in usage["effectiveLimits"] if item["tenantId"] == tenant_id)


def test_usage_accrues_and_limits_inherit(client, admin_token, editor_token):
    before = _usage(client, admin_token)
    before_tokens = _row(before, SPACE_A)["tokensUsed"]
    before_calls = _row(before, SPACE_A)["callsUsed"]

    hdrs = headers(editor_token, SPACE_A)
    wf = client.post("/api/v1/workflows", headers=hdrs,
                     json={"name": f"e2e-usage-{uuid.uuid4().hex[:8]}"})
    assert wf.status_code == 200, wf.text
    wf_id = wf.json()["data"]["id"]
    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=DEMO_DSL,
                      headers=hdrs).status_code == 200
    version = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    version_id = version.json()["data"]["id"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": version_id},
                       headers=headers(admin_token, SPACE_A)).status_code == 200

    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "成本中心验收"},
                                  "policy": {"tokenBudget": 4000, "maxSteps": 20,
                                             "deadlineMs": 60000}},
                            headers=hdrs)
    assert triggered.status_code == 200, triggered.text
    execution_id = triggered.json()["data"]["executionId"]
    detail = wait_terminal(client, editor_token, SPACE_A, execution_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail

    # 投影器 500ms 一轮，留出余量再查账本
    after = None
    for _ in range(20):
        time.sleep(0.5)
        candidate = _usage(client, admin_token)
        if _row(candidate, SPACE_A)["tokensUsed"] > before_tokens:
            after = candidate
            break
    assert after is not None, "usage did not accrue within 10s"

    row = _row(after, SPACE_A)
    assert row["callsUsed"] >= before_calls + 1
    assert row["tokensUsed"] > before_tokens
    assert row["effectiveTokenLimit"] > 0
    assert 0.0 <= row["tokenUtilization"] <= 1.0


def test_effective_limits_are_inherited_from_ancestors(client, admin_token):
    usage = _usage(client, admin_token)
    space = _row(usage, SPACE_A)
    org = _row(usage, "t-org-demo")
    team = _row(usage, "t-team-core")
    # 子租户有效限额不得高于任一祖先
    assert space["effectiveTokenLimit"] <= org["effectiveTokenLimit"]
    assert space["effectiveTokenLimit"] <= team["effectiveTokenLimit"]
    assert space["type"] == "SPACE"
    assert team["parentId"] == "t-org-demo"
