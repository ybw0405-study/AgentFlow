"""向量索引真库集成：pgvector 迁移、租户/模型谓词隔离、增量与 stale 清理。"""

from __future__ import annotations

import shutil
import subprocess
import uuid

import pytest

from app.config import Settings
from app.db.migrate import run_migrations
from app.events.store import EventStore
from app.rag.vector_store import KnowledgeVectorStore

pytestmark = pytest.mark.integration


def _docker_available() -> bool:
    if not shutil.which("docker"):
        return False
    try:
        subprocess.run(  # noqa: S603 —— 固定命令探测本机 docker，非外部输入
            [shutil.which("docker") or "docker", "info"],
            capture_output=True,
            check=True,
            timeout=10,
        )
    except Exception:
        return False
    return True


if not _docker_available():
    pytest.skip("docker unavailable", allow_module_level=True)


@pytest.fixture(scope="module")
def pg_dsn():
    from testcontainers.community.postgres import PostgresContainer

    with PostgresContainer("pgvector/pgvector:pg16") as pg:
        url = pg.get_connection_url()
        yield url.replace("postgresql+psycopg2://", "postgresql://")


@pytest.fixture
async def store(pg_dsn):
    s = EventStore(pg_dsn)
    await s.connect()
    settings = Settings()
    await run_migrations(s.pool, settings.pg_migrations_path)
    async with s.pool.acquire() as conn:
        await conn.execute("TRUNCATE knowledge_chunk_embedding")
    yield KnowledgeVectorStore(s.pool)
    await s.close()


def _row(cid: str, tenant: str, vector: list[float], content: str = "内容") -> dict:
    return {
        "chunkId": cid,
        "kbId": "kb1",
        "documentId": "doc1",
        "documentTitle": "标题",
        "ordinal": 0,
        "content": content,
        "contentHash": "h-" + cid,
        "embedding": vector,
    }


@pytest.mark.asyncio
async def test_upsert_search_and_cosine_order(store) -> None:
    t = str(uuid.uuid4())
    await store.upsert(
        t,
        "m1",
        [
            _row("c1", t, [1.0] + [0.0] * 1023),
            _row("c2", t, [0.0, 1.0] + [0.0] * 1022),
        ],
    )
    hits = await store.search(t, ["kb1"], "m1", [1.0] + [0.0] * 1023, 5)
    assert [h["chunkId"] for h in hits] == ["c1", "c2"]
    assert hits[0]["score"] == pytest.approx(1.0, abs=1e-6)


@pytest.mark.asyncio
async def test_tenant_and_model_predicates_isolate(store) -> None:
    t1, t2 = str(uuid.uuid4()), str(uuid.uuid4())
    await store.upsert(t1, "m1", [_row("c1", t1, [1.0] + [0.0] * 1023)])
    await store.upsert(t2, "m1", [_row("c2", t2, [1.0] + [0.0] * 1023)])
    await store.upsert(t1, "m2", [_row("c3", t1, [1.0] + [0.0] * 1023)])
    hits = await store.search(t1, ["kb1"], "m1", [1.0] + [0.0] * 1023, 5)
    assert [h["chunkId"] for h in hits] == ["c1"]
    hashes = await store.indexed_hashes(t1, ["kb1"], "m1")
    assert set(hashes) == {"c1"}


@pytest.mark.asyncio
async def test_upsert_is_incremental_and_stale_deleted(store) -> None:
    t = str(uuid.uuid4())
    await store.upsert(
        t,
        "m1",
        [
            _row("c1", t, [1.0] + [0.0] * 1023, "旧"),
            _row("c2", t, [0.0, 1.0] + [0.0] * 1022),
        ],
    )
    await store.upsert(t, "m1", [_row("c1", t, [0.0, 1.0] + [0.0] * 1022, "新")])
    hashes = await store.indexed_hashes(t, ["kb1"], "m1")
    assert hashes["c1"] == "h-c1"
    await store.delete_stale(t, ["kb1"], "m1", {"c1"})
    assert set(await store.indexed_hashes(t, ["kb1"], "m1")) == {"c1"}
    hits = await store.search(t, ["kb1"], "m1", [0.0, 1.0] + [0.0] * 1022, 5)
    assert hits[0]["content"] == "新"
