"""E2E：vector / hybrid 检索模式真跑（pgvector 懒索引 + RRF 融合）。

替身端点同时提供 /embeddings（确定性词袋向量），引擎走真实 HTTP 与真实 PG。
"""

from __future__ import annotations

import uuid

import pytest

from .conftest import (
    REAL_LLM, REAL_LLM_MODEL, SPACE_A,
    headers, new_idem, wait_terminal,
)

DOCUMENT = """退款政策
用户购买后七天内可以申请全额退款，请在订单页提交退款申请并提交订单编号。

发票问题
发票开具需要三个工作日，请联系财务组处理，逾期会自动补开。
"""

REFUND_LINE = "七天内可以申请全额退款"


def _dsl(kb_id: str, mode: str) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {"question": {"type": "string", "required": True}},
        "defaults": {"timeoutMs": 30000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {"key": "retrieve", "type": "retrieval", "config": {
                "knowledgeBaseIds": [kb_id],
                "queryTemplate": "{{ vars.question }}",
                "topK": 3,
                "minScore": 0,
                "retrievalMode": mode,
            }},
            {"key": "answer", "type": "llm", "config": {
                "provider": "openai-compatible",
                "model": REAL_LLM_MODEL,
                "promptTemplate": "依据文档回答：{{ nodes.retrieve.output.text }}\n问题：{{ vars.question }}",
                "maxTokens": 512,
            }},
            {"key": "end", "type": "end", "config": {
                "resultMapping": {
                    "answer": "{{ nodes.answer.output.text }}",
                    "sources": "{{ nodes.retrieve.output.text }}",
                    "mode": "{{ nodes.retrieve.output.retrievalMode }}",
                },
            }},
        ],
        "edges": [
            {"from": "start", "to": "retrieve"},
            {"from": "retrieve", "to": "answer"},
            {"from": "answer", "to": "end"},
        ],
    }


@pytest.mark.skipif(not REAL_LLM, reason="需要替身/真实模型端点：AF_E2E_REAL_LLM=1 并注入 AF_LLM_* 与 AF_EMBEDDING_*")
@pytest.mark.parametrize("mode", ["hybrid", "vector"])
def test_vector_and_hybrid_retrieval_return_real_chunks(client, admin_token, editor_token, mode):
    kb = client.post("/api/v1/knowledge/bases", headers=headers(admin_token, SPACE_A),
                     json={"name": f"e2e-vec-{uuid.uuid4().hex[:8]}", "description": "e2e"})
    assert kb.status_code == 200, kb.text
    kb_id = kb.json()["data"]["id"]
    try:
        doc = client.post(f"/api/v1/knowledge/bases/{kb_id}/documents",
                          headers=headers(admin_token, SPACE_A),
                          json={"title": "售后手册", "content": DOCUMENT})
        assert doc.status_code == 200, doc.text

        wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                         json={"name": f"e2e-vec-wf-{uuid.uuid4().hex[:6]}"})
        assert wf.status_code == 200, wf.text
        wf_id = wf.json()["data"]["id"]
        assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=_dsl(kb_id, mode),
                          headers=headers(editor_token, SPACE_A)).status_code == 200
        version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                              headers=headers(editor_token, SPACE_A))
        assert version.status_code == 200, version.text
        assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                           json={"versionId": version.json()["data"]["id"]},
                           headers=headers(admin_token, SPACE_A)).status_code == 200

        triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                                json={"idempotencyKey": new_idem(),
                                      "variables": {"question": "退款怎么申请"},
                                      "policy": {"tokenBudget": 4000, "maxSteps": 10,
                                                 "deadlineMs": 30000}},
                                headers=headers(editor_token, SPACE_A))
        assert triggered.status_code == 200, triggered.text
        execution_id = triggered.json()["data"]["executionId"]

        detail = wait_terminal(client, editor_token, SPACE_A, execution_id, timeout_s=60)
        assert detail["execution"]["status"] == "SUCCEEDED", detail
        assert detail["result"]["mode"] == mode, detail["result"]
        assert REFUND_LINE in detail["result"]["sources"], detail["result"]

        io = client.get(f"/api/v1/executions/{execution_id}/nodes/retrieve/io",
                        headers=headers(editor_token, SPACE_A))
        assert io.status_code == 200, io.text
        snapshot = io.json()["data"]["in"]["payload"]
        assert snapshot["retrievalMode"] == mode, snapshot
    finally:
        client.delete(f"/api/v1/knowledge/bases/{kb_id}", headers=headers(admin_token, SPACE_A))
