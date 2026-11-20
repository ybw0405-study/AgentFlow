"""retrieval 节点与文档检索客户端：真实切片、错误分类、阶段门。"""

from __future__ import annotations

import json
import uuid
from typing import Any

import httpx
import pytest

from app.commands.providers.knowledge_search import KnowledgeSearchClient
from app.config import Settings
from app.dsl.models import RetrievalConfig, RetrievalNode
from app.dsl.validate import validate_dsl
from app.errors import PermanentNodeError, TransientNodeError
from app.nodes.base import NodeContext, Policy
from app.nodes.retrieval import RetrievalExecutor, build_context

INTERNAL = "dev-internal-token"


class FakeClient:
    def __init__(self, data: dict[str, Any] | None = None, error: Exception | None = None):
        self.data = (
            data
            if data is not None
            else {"results": [], "query": "", "matchedChunks": 0, "scannedChunks": 0}
        )
        self.error = error
        self.calls: list[dict[str, Any]] = []

    async def search(self, **kwargs: Any) -> dict[str, Any]:
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.data


def _node(query_template: str = "{{ vars.question }}") -> RetrievalNode:
    return RetrievalNode(
        key="kb",
        type="retrieval",
        config=RetrievalConfig(
            knowledge_base_ids=["kb-1"], query_template=query_template, top_k=3, min_score=0.0
        ),
    )


def _ctx() -> tuple[NodeContext, list[tuple[Any, dict[str, Any]]]]:
    events: list[tuple[Any, dict[str, Any]]] = []

    async def emit(event_type: Any, payload: dict[str, Any], *, node_key: str | None = None):
        events.append((event_type, payload))

    ctx = NodeContext(
        execution_id=uuid.uuid4(),
        node_key="kb",
        attempt=1,
        seed="seed",
        trace_id="trace-1",
        variables={"question": "退款流程怎么走"},
        variables_spec={},
        outputs={},
        node_statuses={},
        policy=Policy(),
        budget=None,
        emit=emit,
        space_id="space-1",
    )
    return ctx, events


def _hit(content: str) -> dict[str, Any]:
    return {
        "chunkId": "c-1",
        "documentId": "doc-1",
        "documentTitle": "售后手册",
        "kbId": "kb-1",
        "ordinal": 2,
        "content": content,
        "score": 0.78,
    }


async def test_retrieval_returns_real_document_chunks():
    client = FakeClient(
        {
            "results": [_hit("退款需在订单页提交申请，七天内全额退款。")],
            "query": "退款流程怎么走",
            "matchedChunks": 1,
            "scannedChunks": 12,
        }
    )
    ctx, events = _ctx()
    result = await RetrievalExecutor(client).execute(_node(), ctx)

    assert "退款需在订单页提交申请" in result.output["text"]
    assert "《售后手册》" in result.output["text"]
    assert result.output["citations"][0]["documentId"] == "doc-1"
    assert result.output["result"]["results"][0]["score"] == 0.78
    assert client.calls[0]["query"] == "退款流程怎么走"
    assert client.calls[0]["tenant_id"] == "space-1"
    assert [str(e[0]) for e in events] == ["CommandIssued", "CommandCompleted"]


async def test_retrieval_renders_query_from_prior_nodes():
    client = FakeClient()
    ctx, _ = _ctx()
    ctx.outputs["draft"] = {"text": "用户问退款"}
    await RetrievalExecutor(client).execute(_node("{{ nodes.draft.output.text }}"), ctx)
    assert client.calls[0]["query"] == "用户问退款"


async def test_empty_hits_is_not_an_error():
    ctx, events = _ctx()
    result = await RetrievalExecutor(FakeClient()).execute(_node(), ctx)
    assert result.output["results"] == []
    assert result.output["text"] == ""
    assert str(events[-1][0]) == "CommandCompleted"


async def test_missing_configuration_fails_loudly():
    ctx, _ = _ctx()
    with pytest.raises(PermanentNodeError) as exc:
        await RetrievalExecutor(None).execute(_node(), ctx)
    assert exc.value.code == "RETRIEVAL-NOT_CONFIGURED-001"


async def test_upstream_failure_emits_failed_and_stays_transient():
    client = FakeClient(error=TransientNodeError("RETRIEVAL-TIMEOUT-001", "timeout"))
    ctx, events = _ctx()
    with pytest.raises(TransientNodeError):
        await RetrievalExecutor(client).execute(_node(), ctx)
    assert str(events[-1][0]) == "CommandFailed"
    assert events[-1][1]["errorCode"] == "RETRIEVAL-TIMEOUT-001"


def test_build_context_caps_length():
    text = build_context([_hit("很长的内容" * 5000) for _ in range(4)])
    assert len(text) <= 32 * 1024 + 200
    assert text.count("《售后手册》") < 4


async def test_client_unwraps_envelope_and_sends_tenant_scope():
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        captured["token"] = request.headers.get("X-Internal-Token")
        captured["json"] = json.loads(request.content)
        return httpx.Response(
            200,
            json={
                "code": "OK",
                "msg": "success",
                "traceId": "t",
                "data": {
                    "results": [_hit("真实文档内容")],
                    "query": "退款",
                    "matchedChunks": 1,
                    "scannedChunks": 4,
                },
            },
        )

    client = KnowledgeSearchClient(
        "http://gateway", INTERNAL, transport=httpx.MockTransport(handler)
    )
    data = await client.search(
        tenant_id="space-1", knowledge_base_ids=["kb-1"], query="退款", top_k=5, min_score=0.0
    )
    assert captured["path"] == "/internal/knowledge/search"
    assert captured["token"] == INTERNAL
    assert captured["json"]["tenantId"] == "space-1"
    assert data["results"][0]["content"] == "真实文档内容"
    await client.aclose()


async def test_client_rejects_malformed_response():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"code": "OK", "data": {"unexpected": 1}})

    client = KnowledgeSearchClient(
        "http://gateway", INTERNAL, transport=httpx.MockTransport(handler)
    )
    with pytest.raises(PermanentNodeError) as exc:
        await client.search(
            tenant_id="space-1", knowledge_base_ids=["kb-1"], query="q", top_k=5, min_score=0.0
        )
    assert exc.value.code == "RETRIEVAL-RESPONSE-001"
    await client.aclose()


async def test_client_upstream_5xx_is_transient():
    def handler(_: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"status": 503})

    client = KnowledgeSearchClient(
        "http://gateway", INTERNAL, transport=httpx.MockTransport(handler)
    )
    with pytest.raises(TransientNodeError) as exc:
        await client.search(
            tenant_id="space-1", knowledge_base_ids=["kb-1"], query="q", top_k=5, min_score=0.0
        )
    assert exc.value.code == "RETRIEVAL-UPSTREAM-001"
    await client.aclose()


def test_phase_gate_accepts_retrieval_node():
    dsl = {
        "dslVersion": "1.0",
        "variables": {"question": {"type": "string", "required": True}},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "kb",
                "type": "retrieval",
                "config": {"knowledgeBaseIds": ["kb-1"], "queryTemplate": "{{ vars.question }}"},
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ nodes.kb.output.text }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "kb"}, {"from": "kb", "to": "end"}],
    }
    result = validate_dsl(dsl, Settings())
    assert result.ok, [e.message for e in result.errors]
