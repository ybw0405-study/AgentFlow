"""retrieval 节点：从用户导入的文档里做真实检索，交给下游 llm 节点引用。

与固定示例语料的区别：这里返回的每一条 content 都来自知识库切片，
排序由控制面的 BM25 打分决定，不是固定模板拼接出来的示例文本。
"""

from __future__ import annotations

import time
from typing import Any, Protocol

from ..commands.base import CommandKind, digest_of, make_call_id
from ..dsl.models import RetrievalNode
from ..errors import PermanentNodeError, TransientNodeError
from ..events.types import EventType
from ..expression.template import render_template
from .base import NodeContext, NodeResult

MAX_CONTEXT_CHARS = 32 * 1024


class KnowledgeSearchPort(Protocol):
    async def search(
        self,
        *,
        tenant_id: str,
        knowledge_base_ids: list[str],
        query: str,
        top_k: int,
        min_score: float,
        trace_id: str = "",
    ) -> dict[str, Any]: ...


def build_context(results: list[dict[str, Any]]) -> str:
    """把命中切片拼成可直接塞进提示词的带出处上下文。"""
    parts: list[str] = []
    used = 0
    for index, hit in enumerate(results, start=1):
        title = str(hit.get("documentTitle") or "文档")
        ordinal = hit.get("ordinal", 0)
        score = hit.get("score", 0.0)
        content = str(hit.get("content") or "").strip()
        header = f"[{index}] 《{title}》 片段 {int(ordinal) + 1}（相关度 {float(score):.2f}）"
        block = f"{header}\n{content}"
        if used + len(block) > MAX_CONTEXT_CHARS:
            break
        parts.append(block)
        used += len(block)
    return "\n\n".join(parts)


class RetrievalExecutor:
    node_type = "retrieval"

    def __init__(self, client: KnowledgeSearchPort | None) -> None:
        self._client = client

    async def aclose(self) -> None:
        closer = getattr(self._client, "aclose", None)
        if closer is not None:
            await closer()

    async def execute(self, node: RetrievalNode, ctx: NodeContext) -> NodeResult:
        if self._client is None:
            raise PermanentNodeError(
                "RETRIEVAL-NOT_CONFIGURED-001", "knowledge search is not enabled"
            )
        cfg = node.config
        query = render_template(cfg.query_template, ctx.template_context())
        call_id = make_call_id(ctx.execution_id, ctx.node_key, ctx.attempt)
        await ctx.emit(
            EventType.COMMAND_ISSUED,
            {
                "callId": call_id,
                "kind": str(CommandKind.RETRIEVAL),
                "knowledgeBaseIds": list(cfg.knowledge_base_ids),
                "queryDigest": digest_of(query),
                "attempt": ctx.attempt,
            },
        )
        started = time.monotonic()
        try:
            data = await self._client.search(
                tenant_id=ctx.space_id,
                knowledge_base_ids=list(cfg.knowledge_base_ids),
                query=query,
                top_k=cfg.top_k,
                min_score=cfg.min_score,
                trace_id=ctx.trace_id,
            )
        except (TransientNodeError, PermanentNodeError) as exc:
            await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": exc.code})
            raise

        results = [r for r in data.get("results", []) if isinstance(r, dict)]
        citations = [
            {
                "chunkId": r.get("chunkId"),
                "documentId": r.get("documentId"),
                "documentTitle": r.get("documentTitle"),
                "ordinal": r.get("ordinal"),
                "score": r.get("score"),
            }
            for r in results
        ]
        text = build_context(results)
        await ctx.emit(
            EventType.COMMAND_COMPLETED,
            {
                "callId": call_id,
                "resultCount": len(results),
                "responseDigest": digest_of(data),
                "latencyMs": int((time.monotonic() - started) * 1000),
            },
        )
        payload = {
            "results": results,
            "query": query,
            "matchedChunks": data.get("matchedChunks", len(results)),
            "scannedChunks": data.get("scannedChunks", len(results)),
        }
        return NodeResult(
            output={
                # result 保持与 tool 节点同形，模板可直接沿用 nodes.xxx.output.result
                "result": payload,
                "results": results,
                "citations": citations,
                "text": text,
                "query": query,
                "matchedChunks": payload["matchedChunks"],
                "scannedChunks": payload["scannedChunks"],
            },
            input_snapshot={
                "knowledgeBaseIds": list(cfg.knowledge_base_ids),
                "queryTemplate": cfg.query_template,
                "query": query,
                "topK": cfg.top_k,
                "minScore": cfg.min_score,
            },
        )
