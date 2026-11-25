"""回归：/internal/events 全局事件尾读的 spaceId 过滤下推。

覆盖四层：真实 EventStore 的 SQL、内存替身语义、HTTP 端点接线、
以及「Java 调用签名 ↔ 引擎端点 ↔ OpenAPI」的跨服务契约。
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest
from httpx import ASGITransport, AsyncClient

from app.events.store import EventStore

ROOT = Path(__file__).resolve().parents[3]


class _CapturingConn:
    """捕获 EventStore 生成的 SQL 与绑定参数（不连真实 PG）。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, tuple]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def fetch(self, sql, *args):
        self.calls.append((sql, args))
        return []


class _CapturingPool:
    def __init__(self, conn: _CapturingConn) -> None:
        self._conn = conn

    def acquire(self):
        return self._conn


@pytest.mark.asyncio
async def test_tail_pushes_space_predicate_into_sql():
    conn = _CapturingConn()
    store = EventStore("postgresql://unused")
    store._pool = _CapturingPool(conn)

    await store.tail(0, 10, space_id="space-a")
    sql, args = conn.calls[-1]
    assert "EXISTS" in sql
    assert "execution_spec" in sql
    assert "spec->>'spaceId'" in sql
    assert args == (0, "space-a", 10)

    # space_id=None 保持系统级全局投影的原 SQL（无空间谓词、参数仍是 2 个）
    await store.tail(0, 10)
    sql, args = conn.calls[-1]
    assert "execution_spec" not in sql
    assert args == (0, 10)


@pytest.mark.asyncio
async def test_inmemory_tail_is_scoped_and_fail_closed(mem_store):
    a, b, orphan = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    await mem_store.put_spec(a, {"spaceId": "space-a"})
    await mem_store.put_spec(b, {"spaceId": "space-b"})
    # orphan 没有 spec：无法证明空间归属，任何按空间读取里都不能出现
    for eid in (a, b, orphan):
        await mem_store.append(eid, "NodeStarted", {"i": 1})

    assert len(await mem_store.tail(0, 100)) == 3  # 不传 space_id：全局语义不变
    assert [e.execution_id for e in await mem_store.tail(0, 100, space_id="space-a")] == [a]
    assert [e.execution_id for e in await mem_store.tail(0, 100, space_id="space-b")] == [b]
    assert await mem_store.tail(0, 100, space_id="space-z") == []
    for space in ("space-a", "space-b"):
        assert all(e.execution_id != orphan for e in await mem_store.tail(0, 100, space_id=space))


@pytest.mark.asyncio
async def test_api_tail_events_forwards_space_id(settings, mem_store):
    from app.main import create_app

    a, b = uuid.uuid4(), uuid.uuid4()
    await mem_store.put_spec(a, {"spaceId": "space-a"})
    await mem_store.put_spec(b, {"spaceId": "space-b"})
    await mem_store.append(a, "NodeStarted", {"i": 1})
    await mem_store.append(b, "NodeStarted", {"i": 2})

    app = create_app(settings)
    app.state.settings = settings
    app.state.store = mem_store
    headers = {"X-Internal-Token": settings.internal_token}
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        scoped = await client.get(
            "/internal/events", params={"spaceId": "space-a"}, headers=headers
        )
        assert scoped.status_code == 200
        assert [e["executionId"] for e in scoped.json()["events"]] == [str(a)]

        global_page = await client.get("/internal/events", headers=headers)
        assert len(global_page.json()["events"]) == 2

        blank = await client.get("/internal/events", params={"spaceId": " "}, headers=headers)
        assert blank.status_code == 200
        assert len(blank.json()["events"]) == 2  # 空白 spaceId 视为未指定


def test_af3_space_id_contract_across_services():
    """契约：Java 客户端签名/URI、引擎端点、OpenAPI 三处必须同时带 spaceId。"""
    client_src = (
        ROOT / "workflow-service/src/main/java/com/agentflow/workflow/engine/"
        "EngineGatewayClient.java"
    ).read_text(encoding="utf-8")
    projector_src = (
        ROOT / "workflow-service/src/main/java/com/agentflow/workflow/projector/EventProjector.java"
    ).read_text(encoding="utf-8")
    internal_src = (ROOT / "engine-api/app/api/internal.py").read_text(encoding="utf-8")

    assert "tailEvents(long afterSeq, int limit, String spaceId)" in client_src
    assert "spaceId={s}" in client_src
    assert "engine.tailEvents(cursor, pageSize, spaceId)" in projector_src
    assert 'Query(None, alias="spaceId")' in internal_src
    assert "tail(after, limit, space_id=scope)" in internal_src

    spec = json.loads((ROOT / "docs/openapi/engine-api.json").read_text(encoding="utf-8"))
    params = spec["paths"]["/internal/events"]["get"]["parameters"]
    assert any(p["name"] == "spaceId" for p in params), "OpenAPI 基线缺少 spaceId 参数"
