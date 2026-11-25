"""语义检索单元回归：embedding 错误分类、RRF 融合、懒索引增量与模式分派。"""

from __future__ import annotations

import httpx
import pytest

from app.dsl.models import RetrievalNode
from app.errors import PermanentNodeError, TransientNodeError
from app.rag.embedding import OpenAIEmbeddingProvider
from app.rag.fusion import rrf_fuse
from app.rag.rerank import OpenAIRerankProvider


def provider_with(handler) -> OpenAIEmbeddingProvider:
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return OpenAIEmbeddingProvider("http://127.0.0.1:1/v1", "k", "test-embed", 4, client=client)


@pytest.mark.asyncio
async def test_embed_returns_vectors_in_request_order() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "data": [
                    {"index": 1, "embedding": [0.0, 0.0, 0.0, 1.0]},
                    {"index": 0, "embedding": [1.0, 0.0, 0.0, 0.0]},
                ]
            },
        )

    vectors = await provider_with(handler).embed(["a", "b"], timeout_s=5)
    assert vectors[0] == [1.0, 0.0, 0.0, 0.0]
    assert vectors[1] == [0.0, 0.0, 0.0, 1.0]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "kind"),
    [
        (401, PermanentNodeError),
        (403, PermanentNodeError),
        (422, PermanentNodeError),
        (429, TransientNodeError),
        (503, TransientNodeError),
    ],
)
async def test_embed_error_classification(status: int, kind: type) -> None:
    provider = provider_with(lambda req: httpx.Response(status))
    with pytest.raises(kind):
        await provider.embed(["a"], timeout_s=5)


@pytest.mark.asyncio
async def test_embed_dimension_mismatch_is_permanent() -> None:
    provider = provider_with(
        lambda req: httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0]}]})
    )
    with pytest.raises(PermanentNodeError):
        await provider.embed(["a"], timeout_s=5)


def test_rrf_fuse_merges_by_chunk_and_orders_by_fused_score() -> None:
    lexical = [{"chunkId": "a", "score": 0.9}, {"chunkId": "b", "score": 0.5}]
    vector = [{"chunkId": "b", "score": 0.8}, {"chunkId": "c", "score": 0.7}]
    fused = rrf_fuse(lexical, vector, top_k=3)
    assert [hit["chunkId"] for hit in fused] == ["b", "a", "c"]
    assert fused[0]["score"] == pytest.approx(1 / 62 + 1 / 61, abs=1e-6)


def test_rrf_fuse_respects_top_k() -> None:
    lexical = [{"chunkId": str(i)} for i in range(10)]
    assert len(rrf_fuse(lexical, [], top_k=4)) == 4


class _FakeStore:
    def __init__(self) -> None:
        self.rows: dict[str, dict] = {}
        self.deleted: list[str] = []

    async def indexed_hashes(self, tenant_id, kb_ids, model):
        return {cid: row["contentHash"] for cid, row in self.rows.items()}

    async def upsert(self, tenant_id, model, rows):
        for row in rows:
            self.rows[row["chunkId"]] = row

    async def delete_stale(self, tenant_id, kb_ids, model, keep):
        self.deleted = [cid for cid in self.rows if cid not in keep]
        for cid in self.deleted:
            del self.rows[cid]

    async def search(self, tenant_id, kb_ids, model, query_vector, top_k):
        return [
            {
                "chunkId": cid,
                "kbId": row["kbId"],
                "documentId": row["documentId"],
                "documentTitle": row["documentTitle"],
                "ordinal": row["ordinal"],
                "content": row["content"],
                "score": 0.9,
            }
            for cid, row in list(self.rows.items())[:top_k]
        ]


class _FakeEmbedding:
    model = "test-embed"

    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    async def embed(self, texts, timeout_s):
        self.calls.append(list(texts))
        return [[1.0, 0.0, 0.0, 0.0] for _ in texts]


class _FakeClient:
    def __init__(self, chunks, hits=None) -> None:
        self.chunks = chunks
        self.hits = hits or []

    async def search(self, **kwargs):
        return {"results": self.hits, "matchedChunks": len(self.hits), "scannedChunks": 9}

    async def list_chunks(self, **kwargs):
        return self.chunks


def _chunk(cid: str, content: str) -> dict:
    return {
        "chunkId": cid,
        "kbId": "kb1",
        "documentId": "doc1",
        "documentTitle": "标题",
        "ordinal": 0,
        "content": content,
    }


class _Ctx:
    space_id = "t1"
    execution_id = "e1"
    node_key = "r1"
    attempt = 1
    trace_id = ""

    def __init__(self) -> None:
        self.events: list[tuple] = []

    async def emit(self, event_type, payload):
        self.events.append((event_type, payload))

    def template_context(self):
        return {}


def _node(mode: str):
    from app.dsl.models import RetrievalNode

    return RetrievalNode(
        type="retrieval",
        key="r1",
        config={
            "knowledgeBaseIds": ["kb1"],
            "queryTemplate": "问题",
            "topK": 5,
            "minScore": 0.0,
            "retrievalMode": mode,
        },
    )


@pytest.mark.asyncio
async def test_vector_mode_indexes_lazily_and_reuses_cache() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    store, embedding = _FakeStore(), _FakeEmbedding()
    client = _FakeClient([_chunk("c1", "甲"), _chunk("c2", "乙")])
    executor = RetrievalExecutor(client, embedding, store)
    result = await executor.execute(_node("vector"), _Ctx())
    assert result.output["retrievalMode"] == "vector"
    assert len(embedding.calls) == 2  # 一批索引 + 一次查询
    assert len(store.rows) == 2
    again = await executor.execute(_node("vector"), _Ctx())
    assert len(embedding.calls) == 3  # 第二次只嵌查询，索引命中缓存
    assert again.output["results"]


@pytest.mark.asyncio
async def test_vector_mode_reembeds_changed_content_and_deletes_stale() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    store, embedding = _FakeStore(), _FakeEmbedding()
    client = _FakeClient([_chunk("c1", "甲"), _chunk("c2", "乙")])
    executor = RetrievalExecutor(client, embedding, store)
    await executor.execute(_node("vector"), _Ctx())
    client.chunks = [_chunk("c1", "甲改")]
    await executor.execute(_node("vector"), _Ctx())
    assert store.deleted == ["c2"]
    assert store.rows["c1"]["content"] == "甲改"


@pytest.mark.asyncio
async def test_hybrid_mode_fuses_lexical_and_vector() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    store, embedding = _FakeStore(), _FakeEmbedding()
    client = _FakeClient([_chunk("c1", "甲")], hits=[_chunk("c9", "词法命中")])
    executor = RetrievalExecutor(client, embedding, store)
    result = await executor.execute(_node("hybrid"), _Ctx())
    ids = [hit["chunkId"] for hit in result.output["results"]]
    assert set(ids) == {"c1", "c9"}


@pytest.mark.asyncio
async def test_vector_mode_without_provider_is_explicit_error() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    executor = RetrievalExecutor(_FakeClient([]))
    with pytest.raises(PermanentNodeError) as exc:
        await executor.execute(_node("vector"), _Ctx())
    assert exc.value.code == "RETRIEVAL-VECTOR-NOT_CONFIGURED-001"


@pytest.mark.asyncio
async def test_index_cap_refuses_oversized_knowledge_base() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    client = _FakeClient([_chunk(f"c{i}", "内容") for i in range(3)])
    executor = RetrievalExecutor(client, _FakeEmbedding(), _FakeStore(), index_cap=2)
    with pytest.raises(PermanentNodeError) as exc:
        await executor.execute(_node("vector"), _Ctx())
    assert exc.value.code == "RETRIEVAL-INDEX-CAP-001"


@pytest.mark.asyncio
async def test_lexical_mode_unchanged_without_embedding() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    client = _FakeClient([], hits=[_chunk("c9", "词法命中")])
    executor = RetrievalExecutor(client)
    result = await executor.execute(_node("lexical"), _Ctx())
    assert result.output["retrievalMode"] == "lexical"
    assert [hit["chunkId"] for hit in result.output["results"]] == ["c9"]


# ---------------------------------------------------------------------------
# rerank：provider 解析、错误分类、未配置报错、重排截断
# ---------------------------------------------------------------------------


def _rerank_provider_with(handler) -> OpenAIRerankProvider:
    from app.rag.rerank import OpenAIRerankProvider

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return OpenAIRerankProvider("http://127.0.0.1:1/v1", "k", "test-rerank", client=client)


@pytest.mark.asyncio
async def test_rerank_returns_items_sorted_by_score() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "results": [
                    {"index": 1, "relevance_score": 0.9},
                    {"index": 0, "relevance_score": 0.5},
                ]
            },
        )

    items = await _rerank_provider_with(handler).rerank("q", ["a", "b"], timeout_s=5)
    assert [index for index, _ in items] == [1, 0]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "kind"),
    [
        (401, PermanentNodeError),
        (422, PermanentNodeError),
        (429, TransientNodeError),
        (503, TransientNodeError),
    ],
)
async def test_rerank_error_classification(status: int, kind: type) -> None:
    provider = _rerank_provider_with(lambda req: httpx.Response(status))
    with pytest.raises(kind):
        await provider.rerank("q", ["a"], timeout_s=5)


class _FakeReranker:
    model = "test-rerank"

    def __init__(self, order: list[int]) -> None:
        self._order = list(order)
        self.calls = 0

    async def rerank(self, query, documents, timeout_s):
        self.calls += 1
        return [(index, 0.0) for index in self._order]


def _node_rerank(mode: str) -> RetrievalNode:
    return RetrievalNode(
        type="retrieval",
        key="r1",
        config={
            "knowledgeBaseIds": ["kb1"],
            "queryTemplate": "问题",
            "topK": 3,
            "minScore": 0.0,
            "retrievalMode": mode,
            "rerank": True,
        },
    )


@pytest.mark.asyncio
async def test_rerank_reorders_then_truncates_to_top_k() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    hits = [_chunk(f"c{i}", f"内容{i}") for i in range(4)]
    client = _FakeClient([], hits=hits)
    reranker = _FakeReranker(order=[3, 1, 0, 2])
    executor = RetrievalExecutor(client, reranker=reranker, rerank_candidate_factor=2)
    result = await executor.execute(_node_rerank("lexical"), _Ctx())
    ids = [hit["chunkId"] for hit in result.output["results"]]
    assert ids == ["c3", "c1", "c0"]  # rerank 顺序取 top 3
    assert reranker.calls == 1


@pytest.mark.asyncio
async def test_rerank_without_provider_is_explicit_error() -> None:
    from app.nodes.retrieval import RetrievalExecutor

    executor = RetrievalExecutor(_FakeClient([]))
    with pytest.raises(PermanentNodeError) as exc:
        await executor.execute(_node_rerank("lexical"), _Ctx())
    assert exc.value.code == "RERANK-NOT_CONFIGURED-001"
