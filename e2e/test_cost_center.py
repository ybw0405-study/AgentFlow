"""P2-E2E：成本中心 —— 节点完成事件经投影器累加到租户月度账本。"""

from __future__ import annotations

import time
import uuid

from .conftest import DEMO_DSL, REAL_LLM_MODEL, SPACE_A, headers, new_idem, wait_terminal


def _usage(client, admin_token: str) -> dict:
    resp = client.get("/api/v1/admin/usage", headers=headers(admin_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


def _row(usage: dict, tenant_id: str) -> dict:
    return next(item for item in usage["tenants"] if item["tenantId"] == tenant_id)


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


def test_usage_breaks_down_by_model(client, editor_token):
    """按模型分解：执行侧模型来自节点索引，与试跑侧合并，账本份额能切成两段。"""
    summary = client.get("/api/v1/usage/summary", params={"days": 7},
                         headers=headers(editor_token, SPACE_A))
    assert summary.status_code == 200, summary.text
    data = summary.json()["data"]

    rows = data["byModel"]
    assert rows, data
    # 上面那条用例已经跑过一次 DEMO_DSL，两个 llm 节点都该记在同一个模型下
    llm = next((row for row in rows if row.get("model") == REAL_LLM_MODEL), None)
    assert llm is not None, rows
    assert llm["executions"] >= 2, llm
    assert llm["executionTokens"] > 0, llm
    assert llm["tokens"] >= llm["executionTokens"] + llm.get("previewTokens", 0), llm

    # 试跑份额是账本的一部分，不能比已用量还大（大了说明账本与明细不同步）
    budget = data["budget"]
    assert budget.get("previewTokens", 0) <= budget["tokensUsed"], budget
    assert budget.get("previewCalls", 0) <= budget["callsUsed"], budget
    assert 0.0 <= budget.get("previewTokenUtilization", 0) <= 1.0, budget


def test_effective_limits_are_inherited_from_ancestors(client, admin_token):
    usage = _usage(client, admin_token)
    space = _row(usage, SPACE_A)
    org = _row(usage, "t-org-demo")
    team = _row(usage, "t-team-core")

    def limit(row: dict, key: str) -> float:
        # 无上限的字段后端不下发，当作无穷大比较
        value = row.get(key)
        return float("inf") if value is None else value

    # 子租户有效限额不得高于任一祖先
    assert limit(space, "effectiveTokenLimit") <= limit(org, "effectiveTokenLimit")
    assert limit(space, "effectiveTokenLimit") <= limit(team, "effectiveTokenLimit")
    assert space["type"] == "SPACE"
    assert team["parentId"] == "t-org-demo"

    # 限额来源能指到具体租户：自身最紧写自己，祖先最紧写祖先
    assert space["limitedByToken"] in {SPACE_A, "t-org-demo", "t-team-core"}
    assert space["inherited"] == (space["limitedByToken"] != SPACE_A)
    assert space["state"] in {"ok", "near", "over", "unbounded"}


def test_admin_usage_rejects_malformed_period(client, admin_token):
    resp = client.get("/api/v1/admin/usage", params={"period": "2026-8"},
                      headers=headers(admin_token, SPACE_A))
    assert resp.status_code == 400, resp.text
    assert "YYYY-MM" in resp.json()["detail"], resp.text


def test_admin_usage_breaks_down_by_model_across_tenants(client, admin_token):
    usage = _usage(client, admin_token)
    totals = usage["totals"]
    rows = usage["byModel"]

    # 整页是账期口径：平台合计等于各租户之和
    assert totals["tokens"] == sum(item["tokensUsed"] for item in usage["tenants"]), totals
    assert totals["previewTokens"] == sum(item.get("previewTokens", 0) for item in usage["tenants"])
    assert totals["over"] + totals["near"] <= totals["tenants"]

    llm = next((row for row in rows if row.get("model") == REAL_LLM_MODEL), None)
    assert llm is not None, rows
    assert llm["executionTokens"] > 0, llm
    assert llm["tenants"] >= 1, llm
    # 试跑侧与执行侧是并集：租户数不会超过实际有账本行的租户数
    assert llm["tenants"] <= len(usage["tenants"])
