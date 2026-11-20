"""E2E：文档知识库 → retrieval 节点真实检索 → 下游节点引用原文。

与 mock.search 的关键区别：这里断言命中的片段来自用户导入的文档，且工作流执行
产出里能看到这段原文，而不是固定模板拼出来的假语料。
"""

from __future__ import annotations

import uuid

from .conftest import (
    INTERNAL_TOKEN, REAL_LLM, REAL_LLM_MODEL, SPACE_A, SPACE_B,
    headers, new_idem, wait_terminal,
)

DOCUMENT = """退款政策
用户购买后七天内可以申请全额退款，请在订单页提交退款申请并提交订单编号。

发票问题
发票开具需要三个工作日，请联系财务组处理，逾期会自动补开。
"""

REFUND_LINE = "七天内可以申请全额退款"


def _rag_dsl(kb_id: str) -> dict:
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
            }},
            {"key": "answer", "type": "llm", "config": {
                "provider": "openai-compatible" if REAL_LLM else "mock",
                "model": REAL_LLM_MODEL if REAL_LLM else "mock-large",
                "promptTemplate": "依据文档回答：{{ nodes.retrieve.output.text }}\n问题：{{ vars.question }}",
                "maxTokens": 128,
            }},
            {"key": "end", "type": "end", "config": {
                "resultMapping": {
                    "answer": "{{ nodes.answer.output.text }}",
                    "sources": "{{ nodes.retrieve.output.text }}",
                },
            }},
        ],
        "edges": [
            {"from": "start", "to": "retrieve"},
            {"from": "retrieve", "to": "answer"},
            {"from": "answer", "to": "end"},
        ],
    }


def _create_base(client, token, name: str) -> str:
    resp = client.post("/api/v1/knowledge/bases", headers=headers(token, SPACE_A),
                       json={"name": name, "description": "e2e"})
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]["id"]


def test_knowledge_crud_search_and_tenant_isolation(client, admin_token, beta_token):
    kb_id = _create_base(client, admin_token, f"e2e-kb-{uuid.uuid4().hex[:8]}")
    try:
        doc = client.post(f"/api/v1/knowledge/bases/{kb_id}/documents",
                          headers=headers(admin_token, SPACE_A),
                          json={"title": "售后手册", "content": DOCUMENT, "source": "TEXT"})
        assert doc.status_code == 200, doc.text
        assert doc.json()["data"]["chunkCount"] >= 1

        listing = client.get("/api/v1/knowledge/bases", headers=headers(admin_token, SPACE_A))
        assert listing.status_code == 200, listing.text
        row = next(r for r in listing.json()["data"] if r["id"] == kb_id)
        assert row["documentCount"] == 1
        assert row["chunkCount"] >= 1

        # 真实检索：命中内容必须来自导入的文档，且查询词影响排序结果
        search = client.post("/api/v1/knowledge/search", headers=headers(admin_token, SPACE_A),
                             json={"knowledgeBaseIds": [kb_id], "query": "退款", "topK": 3})
        assert search.status_code == 200, search.text
        data = search.json()["data"]
        assert data["scannedChunks"] >= 1, data
        assert any(REFUND_LINE in hit["content"] for hit in data["results"]), data
        assert all(hit["documentTitle"] == "售后手册" for hit in data["results"])

        # 跨空间：带上别的租户的 kbId 不应读到任何片段，也不报错暴露存在性
        cross = client.post("/api/v1/knowledge/search", headers=headers(beta_token, SPACE_B),
                            json={"knowledgeBaseIds": [kb_id], "query": "退款", "topK": 3})
        assert cross.status_code == 200, cross.text
        assert cross.json()["data"]["results"] == [], cross.text

        # 删除文档会同步清掉切片
        doc_id = doc.json()["data"]["id"]
        assert client.delete(f"/api/v1/knowledge/documents/{doc_id}",
                             headers=headers(admin_token, SPACE_A)).status_code == 200
        after = client.post("/api/v1/knowledge/search", headers=headers(admin_token, SPACE_A),
                            json={"knowledgeBaseIds": [kb_id], "query": "退款", "topK": 3})
        assert after.json()["data"]["results"] == [], after.text
    finally:
        client.delete(f"/api/v1/knowledge/bases/{kb_id}", headers=headers(admin_token, SPACE_A))


def test_retrieval_node_returns_real_document_text(client, admin_token, editor_token):
    kb_id = _create_base(client, admin_token, f"e2e-rag-{uuid.uuid4().hex[:8]}")
    try:
        doc = client.post(f"/api/v1/knowledge/bases/{kb_id}/documents",
                          headers=headers(admin_token, SPACE_A),
                          json={"title": "退款说明", "content": DOCUMENT})
        assert doc.status_code == 200, doc.text

        wf = client.post("/api/v1/workflows", headers=headers(editor_token, SPACE_A),
                         json={"name": f"e2e-rag-wf-{uuid.uuid4().hex[:6]}"})
        assert wf.status_code == 200, wf.text
        wf_id = wf.json()["data"]["id"]
        saved = client.put(f"/api/v1/workflows/{wf_id}/draft", json=_rag_dsl(kb_id),
                           headers=headers(editor_token, SPACE_A))
        assert saved.status_code == 200, saved.text
        version = client.post(f"/api/v1/workflows/{wf_id}/versions",
                              headers=headers(editor_token, SPACE_A))
        assert version.status_code == 200, version.text
        published = client.post(f"/api/v1/workflows/{wf_id}/publish",
                                json={"versionId": version.json()["data"]["id"]},
                                headers=headers(admin_token, SPACE_A))
        assert published.status_code == 200, published.text

        triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                                json={"idempotencyKey": new_idem(),
                                      "variables": {"question": "退款怎么申请"},
                                      "policy": {"tokenBudget": 4000, "maxSteps": 10,
                                                 "deadlineMs": 30000}},
                                headers=headers(editor_token, SPACE_A))
        assert triggered.status_code == 200, triggered.text
        execution_id = triggered.json()["data"]["executionId"]

        detail = wait_terminal(client, editor_token, SPACE_A, execution_id, timeout_s=40)
        assert detail["execution"]["status"] == "SUCCEEDED", detail
        assert REFUND_LINE in detail["result"]["sources"], detail["result"]
        if REAL_LLM:
            assert "[mock-llm:" not in str(detail["result"]["answer"]), detail["result"]

        # 节点 IO 端点只保留输入快照；产出通过 resultMapping 与下游节点输入验证。
        io = client.get(f"/api/v1/executions/{execution_id}/nodes/retrieve/io",
                        headers=headers(editor_token, SPACE_A))
        assert io.status_code == 200, io.text
        payload = io.json()["data"]
        assert payload["in"]["payload"]["knowledgeBaseIds"] == [kb_id], payload

        answer_io = client.get(f"/api/v1/executions/{execution_id}/nodes/answer/io",
                               headers=headers(editor_token, SPACE_A))
        assert answer_io.status_code == 200, answer_io.text
        prompt = str(answer_io.json()["data"]["in"]["payload"]["prompt"])
        assert REFUND_LINE in prompt, prompt
        assert "mock 语料片段" not in prompt, prompt
    finally:
        client.delete(f"/api/v1/knowledge/bases/{kb_id}", headers=headers(admin_token, SPACE_A))


def test_internal_search_rejects_missing_token(client):
    resp = client.post("/internal/knowledge/search", json={
        "tenantId": SPACE_A, "knowledgeBaseIds": ["kb-not-exist"], "query": "退款",
    })
    assert resp.status_code in (401, 403, 404), resp.text

    # 带令牌时正常返回空结果，而不是把内部端点暴露成公开接口
    ok = client.post("/internal/knowledge/search", json={
        "tenantId": SPACE_A, "knowledgeBaseIds": ["kb-not-exist"], "query": "退款",
    }, headers={"X-Internal-Token": INTERNAL_TOKEN})
    assert ok.status_code == 200, ok.text
    assert ok.json()["data"]["results"] == [], ok.text
