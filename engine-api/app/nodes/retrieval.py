"""retrieval 节点：从用户导入的文档里做真实检索，交给下游 llm 节点引用。

三种模式：lexical（控制面 BM25，默认）、vector（engine 侧 pgvector 语义）、
hybrid（两路 RRF 融合）。vector/hybrid 采用查询触发懒索引：按 content hash
增量嵌入控制面切片，索引与切片缓存落在 engine 侧 PG，控制面仍是权威源。
"""

from __future__ import annotations

import hashlib
import time
from typing import Any, Protocol

from ..commands.base import CommandKind, digest_of, make_call_id
from ..dsl.models import RetrievalNode
from ..errors import PermanentNodeError, TransientNodeError
from ..events.types import EventType
from ..expression.template import render_template
from ..rag.fusion import rrf_fuse
from .base import NodeContext, NodeResult

MAX_CONTEXT_CHARS = 32 * 1024
_EMBED_BATCH = 32


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

    async def list_chunks(
        self,
        *,
        tenant_id: str,
        knowledge_base_ids: list[str],
        limit: int,
        trace_id: str = "",
    ) -> list[dict[str, Any]]: ...


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


def _content_hash(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


class RetrievalExecutor:
    node_type = "retrieval"

    def __init__(
        self,
        client: KnowledgeSearchPort | None,
        embedding=None,
        vector_store=None,
        index_cap: int = 2000,
    ) -> None:
        self._client = client
        self._embedding = embedding
        self._vector_store = vector_store
        self._index_cap = index_cap

    async def aclose(self) -> None:
        closer = getattr(self._client, "aclose", None)
        if closer is not None:
            await closer()
        closer = getattr(self._embedding, "aclose", None)
        if closer is not None:
            await closer()

    async def _ensure_index(
        self, tenant_id: str, kb_ids: list[str], chunks: list[dict[str, Any]], trace_id: str
    ) -> dict[str, int]:
        hashes = {str(c.get("chunkId")): _content_hash(str(c.get("content") or "")) for c in chunks}
        indexed = await self._vector_store.indexed_hashes(tenant_id, kb_ids, self._embedding.model)
        stale = [cid for cid in indexed if cid not in hashes]
        if stale:
            await self._vector_store.delete_stale(
                tenant_id, kb_ids, self._embedding.model, set(hashes)
            )
        todo = [
            c for c in chunks if indexed.get(str(c.get("chunkId"))) != hashes[str(c.get("chunkId"))]
        ]
        embedded = 0
        for start in range(0, len(todo), _EMBED_BATCH):
            batch = todo[start : start + _EMBED_BATCH]
            vectors = await self._embedding.embed(
                [str(c.get("content") or "") for c in batch], timeout_s=10.0
            )
            rows = []
            for chunk, vector in zip(batch, vectors, strict=True):
                rows.append(
                    {
                        "chunkId": str(chunk.get("chunkId")),
                        "kbId": str(chunk.get("kbId")),
                        "documentId": str(chunk.get("documentId")),
                        "documentTitle": str(chunk.get("documentTitle") or "文档"),
                        "ordinal": int(chunk.get("ordinal") or 0),
                        "content": str(chunk.get("content") or ""),
                        "contentHash": hashes[str(chunk.get("chunkId"))],
                        "embedding": vector,
                    }
                )
            await self._vector_store.upsert(tenant_id, self._embedding.model, rows)
            embedded += len(rows)
        return {"embedded": embedded, "cached": len(chunks) - embedded, "deleted": len(stale)}

    async def execute(self, node: RetrievalNode, ctx: NodeContext) -> NodeResult:
        if self._client is None:
            raise PermanentNodeError(
                "RETRIEVAL-NOT_CONFIGURED-001", "knowledge search is not enabled"
            )
        cfg = node.config
        mode = cfg.retrieval_mode
        if mode != "lexical" and (self._embedding is None or self._vector_store is None):
            raise PermanentNodeError(
                "RETRIEVAL-VECTOR-NOT_CONFIGURED-001",
                "vector/hybrid retrieval requires AF_EMBEDDING_BASE_URL and AF_EMBEDDING_API_KEY",
            )
        query = render_template(cfg.query_template, ctx.template_context())
        call_id = make_call_id(ctx.execution_id, ctx.node_key, ctx.attempt)
        kb_ids = list(cfg.knowledge_base_ids)
        await ctx.emit(
            EventType.COMMAND_ISSUED,
            {
                "callId": call_id,
                "kind": str(CommandKind.RETRIEVAL),
                "knowledgeBaseIds": kb_ids,
                "queryDigest": digest_of(query),
                "retrievalMode": mode,
                "attempt": ctx.attempt,
            },
        )
        started = time.monotonic()
        index_stats: dict[str, int] = {}
        try:
            if mode == "lexical":
                data = await self._client.search(
                    tenant_id=ctx.space_id,
                    knowledge_base_ids=kb_ids,
                    query=query,
                    top_k=cfg.top_k,
                    min_score=cfg.min_score,
                    trace_id=ctx.trace_id,
                )
                results = [r for r in data.get("results", []) if isinstance(r, dict)]
            else:
                chunks = await self._client.list_chunks(
                    tenant_id=ctx.space_id,
                    knowledge_base_ids=kb_ids,
                    limit=self._index_cap + 1,
                    trace_id=ctx.trace_id,
                )
                if len(chunks) > self._index_cap:
                    raise PermanentNodeError(
                        "RETRIEVAL-INDEX-CAP-001",
                        f"knowledge chunks exceed index cap {self._index_cap}",
                    )
                index_stats = await self._ensure_index(ctx.space_id, kb_ids, chunks, ctx.trace_id)
                query_vector = (await self._embedding.embed([query], timeout_s=10.0))[0]
                results = await self._vector_store.search(
                    ctx.space_id, kb_ids, self._embedding.model, query_vector, cfg.top_k
                )
                if mode == "hybrid":
                    data = await self._client.search(
                        tenant_id=ctx.space_id,
                        knowledge_base_ids=kb_ids,
                        query=query,
                        top_k=cfg.top_k,
                        min_score=cfg.min_score,
                        trace_id=ctx.trace_id,
                    )
                    lexical = [r for r in data.get("results", []) if isinstance(r, dict)]
                    results = rrf_fuse(lexical, results, cfg.top_k)
                data = {
                    "results": results,
                    "matchedChunks": len(results),
                    "scannedChunks": len(chunks),
                }
        except (TransientNodeError, PermanentNodeError) as exc:
            await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": exc.code})
            raise

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
                "retrievalMode": mode,
                "indexStats": index_stats,
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
                "retrievalMode": mode,
                "matchedChunks": payload["matchedChunks"],
                "scannedChunks": payload["scannedChunks"],
            },
            input_snapshot={
                "knowledgeBaseIds": kb_ids,
                "queryTemplate": cfg.query_template,
                "query": query,
                "topK": cfg.top_k,
                "minScore": cfg.min_score,
                "retrievalMode": mode,
            },
        )
