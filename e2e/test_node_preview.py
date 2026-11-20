"""E2E：单节点试跑。

三条验收线：
1. 试跑要把上游真实跑一遍，回传目标节点的输出与链路（不是空壳）；
2. 试跑不产生执行记录——它是调试动作，不该污染执行列表；
3. 裁剪会改变语义的结构（循环 / 并行 / 审批）明确拒绝，而不是跑出一个不一样的结果。
"""

from __future__ import annotations

import uuid

import pytest

from .conftest import REAL_LLM, REAL_LLM_MODEL, SPACE_A, SPACE_B, headers

TRANSFORM_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 30000, "maxParallelism": 2},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "norm", "type": "transform", "config": {
            "mappings": [{"target": "vars.norm_text", "template": "已收到：{{ vars.question }}"}]}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ vars.norm_text }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "norm"},
        {"from": "norm", "to": "end"},
    ],
}

LLM_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 60000, "maxParallelism": 2},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "draft", "type": "llm", "config": {
            "provider": "openai-compatible",
            "model": REAL_LLM_MODEL,
            "promptTemplate": "用一句话回答：{{ vars.question }}",
            "maxTokens": 512}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ nodes.draft.output.text }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "draft"},
        {"from": "draft", "to": "end"},
    ],
}

LOOP_DSL = {
    "dslVersion": "1.0",
    "variables": {"items": {"type": "array", "required": True}},
    "defaults": {"timeoutMs": 30000, "maxParallelism": 2},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "each", "type": "loop", "config": {
            "itemsExpression": "vars.items",
            "itemVar": "item",
            "indexVar": "index",
            "maxIterations": 10,
            "concurrency": 1,
            "collect": {"into": "tags", "item": "value"},
            "body": {
                "nodes": [
                    {"key": "body_mid", "type": "transform", "config": {
                        "mappings": [{"target": "vars.tag",
                                      "template": "{{ vars.index }}:{{ vars.item }}"}]}},
                    {"key": "body_end", "type": "end", "config": {
                        "resultMapping": {"value": "{{ vars.tag }}"}}},
                ],
                "edges": [{"from": "body_mid", "to": "body_end"}],
            },
        }},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"tags": "{{ vars.tags }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "each"},
        {"from": "each", "to": "end"},
    ],
}


def _create(client, token: str, dsl: dict, space: str = SPACE_A) -> str:
    hdrs = headers(token, space)
    resp = client.post("/api/v1/workflows", json={"name": f"e2e-preview-{uuid.uuid4().hex[:8]}"},
                       headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]
    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=dsl, headers=hdrs)
    assert resp.status_code == 200, resp.text
    return wf_id


def _preview(client, token: str, wf_id: str, key: str, variables: dict,
             space: str = SPACE_A, dsl: dict | None = None):
    return client.post(f"/api/v1/workflows/{wf_id}/nodes/{key}/preview",
                       json={"dsl": dsl, "variables": variables},
                       headers=headers(token, space), timeout=150.0)


def test_preview_runs_upstream_and_reports_trace(client, editor_token):
    wf_id = _create(client, editor_token, TRANSFORM_DSL)

    resp = _preview(client, editor_token, wf_id, "norm", {"question": "你好"})
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["status"] == "SUCCEEDED", data
    assert data["nodeStatus"] == "SUCCEEDED", data
    assert data["output"]["norm_text"] == "已收到：你好", data
    assert [step["key"] for step in data["trace"]] == ["start", "norm"], data
    assert data["latencyMs"] is not None


def test_preview_leaves_no_execution_record(client, editor_token):
    wf_id = _create(client, editor_token, TRANSFORM_DSL)

    resp = _preview(client, editor_token, wf_id, "norm", {"question": "查一下"})
    assert resp.status_code == 200, resp.text
    execution_id = resp.json()["data"]["executionId"]

    listed = client.get("/api/v1/executions", params={"definitionId": wf_id},
                        headers=headers(editor_token, SPACE_A))
    assert listed.status_code == 200, listed.text
    assert [row["id"] for row in listed.json()["data"]["items"]] == []
    assert execution_id not in listed.text


def test_preview_rejects_loop_structure(client, editor_token):
    wf_id = _create(client, editor_token, LOOP_DSL)

    resp = _preview(client, editor_token, wf_id, "each", {"items": ["a", "b"]})
    assert resp.status_code == 422, resp.text
    body = resp.json()
    assert body["code"] == "DSL-VALIDATION-001", body
    assert "循环" in body["detail"], body


def test_preview_is_tenant_scoped(client, editor_token, beta_token):
    wf_id = _create(client, editor_token, TRANSFORM_DSL)

    resp = _preview(client, beta_token, wf_id, "norm", {"question": "越权"}, space=SPACE_B)
    assert resp.status_code == 404, resp.text


@pytest.mark.skipif(not REAL_LLM, reason="需要真实模型端点：设置 AF_E2E_REAL_LLM=1 并注入 AF_LLM_*")
def test_preview_llm_node_returns_real_completion(client, editor_token):
    wf_id = _create(client, editor_token, LLM_DSL)

    resp = _preview(client, editor_token, wf_id, "draft", {"question": "你是谁"})
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["status"] == "SUCCEEDED", data
    text = str((data["output"] or {}).get("text") or "")
    assert text.strip(), data
    assert data["tokenUsed"] > 0, data
    assert data["output"]["model"] == REAL_LLM_MODEL, data


def test_preview_usage_is_counted_in_the_tenant_ledger(client, editor_token):
    """试跑不落执行记录，但消耗必须进账：否则「调试十次」在成本中心是隐形的。"""
    wf_id = _create(client, editor_token, TRANSFORM_DSL)

    before = client.get("/api/v1/usage/summary", params={"days": 7},
                        headers=headers(editor_token, SPACE_A))
    assert before.status_code == 200, before.text
    before_data = before.json()["data"]

    resp = _preview(client, editor_token, wf_id, "norm", {"question": "记账"})
    assert resp.status_code == 200, resp.text

    after = client.get("/api/v1/usage/summary", params={"days": 7},
                       headers=headers(editor_token, SPACE_A))
    assert after.status_code == 200, after.text
    data = after.json()["data"]

    assert data["previews"]["count"] == before_data["previews"]["count"] + 1, data
    recent = data["recentPreviews"][0]
    assert recent["nodeKey"] == "norm", recent
    assert recent["definitionId"] == wf_id, recent
    assert recent["status"] == "SUCCEEDED", recent
    # 试跑的节点数进了调用口径：调试不能白用额度，也不能一条都不记
    assert data["budget"]["callsUsed"] >= before_data["budget"]["callsUsed"] + 2, data
    # 账面上的试跑只能来自这条工作流
    row = next(item for item in data["byDefinition"] if item["definitionId"] == wf_id)
    assert row["previews"] >= 1, row
