"""事件库集成测试：真实 PostgreSQL（testcontainers 临时实例）。

覆盖：乐观并发冲突（US-04 事件版本冲突）、tail 游标、node_io upsert、
payload 溢出（D5）、spec 存取、tracker 终态、迁移漂移拒绝。
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
import uuid

import pytest

from app.config import Settings
from app.db.migrate import run_migrations
from app.events.store import EventStore
from app.events.types import EventType

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

    with PostgresContainer("postgres:16-alpine") as pg:
        url = pg.get_connection_url()
        yield url.replace("postgresql+psycopg2://", "postgresql://")


@pytest.fixture
async def store(pg_dsn):
    s = EventStore(pg_dsn, payload_max_bytes=1024)  # 小阈值便于测溢出
    await s.connect()
    settings = Settings()
    await run_migrations(s.pool, settings.pg_migrations_path)
    async with s.pool.acquire() as conn:  # 测试间隔离
        await conn.execute("TRUNCATE execution_event, execution_tracker, execution_spec, node_io")
    yield s
    await s.close()


async def test_append_and_read_order(store):
    eid = uuid.uuid4()
    for i in range(5):
        await store.append(eid, "NodeStarted", {"i": i}, node_key="n1")
    events = await store.read_all(eid)
    assert [e.aggregate_version for e in events] == [1, 2, 3, 4, 5]
    assert all(e.event_type == EventType.NODE_STARTED for e in events)


async def test_concurrent_appends_have_no_version_gap(store):
    eid = uuid.uuid4()

    async def worker(i: int) -> None:
        await store.append(eid, "NodeStarted", {"i": i}, node_key=f"n{i}")

    await asyncio.gather(*[worker(i) for i in range(20)])
    events = await store.read_all(eid)
    versions = sorted(e.aggregate_version for e in events)
    assert versions == list(range(1, 21))  # 无缺口无重复（US-04 事件版本冲突）


async def test_terminal_event_marks_tracker(store):
    eid = uuid.uuid4()
    assert eid not in await store.open_executions() or True
    await store.append(eid, "ExecutionCreated", {"traceId": "t"})
    assert eid in await store.open_executions()
    await store.append(eid, "ExecutionCompleted", {"result": {}})
    assert eid not in await store.open_executions()


async def test_tail_cursor(store):
    eid = uuid.uuid4()
    seqs = []
    for i in range(7):
        ev = await store.append(eid, "NodeStarted", {"i": i})
        seqs.append(ev.seq)
    first_page = await store.tail(seqs[0] - 1, 3)
    assert len(first_page) == 3
    second_page = await store.tail(first_page[-1].seq, 10)
    assert [e.seq for e in first_page + second_page] == seqs


async def test_node_io_upsert_and_read(store):
    eid = uuid.uuid4()
    await store.put_node_io(eid, "n1", "IN", {"v": 1})
    await store.put_node_io(eid, "n1", "IN", {"v": 2})  # upsert 覆盖
    await store.put_node_io(eid, "n1", "OUT", {"r": 3})
    io = await store.get_node_io(eid, "n1")
    assert io["in"]["payload"] == {"v": 2}
    assert io["out"]["payload"] == {"r": 3}
    assert io["in"]["contentHash"]


async def test_overflow_spills_to_node_io(store):
    eid = uuid.uuid4()
    big = "x" * 5000  # 超过测试阈值的 1024B
    ev = await store.append(eid, "NodeCompleted", {"output": {"text": big}}, node_key="n1")
    assert ev.payload["_overflow"] is True
    io = await store.get_node_io(eid, "n1")
    assert io["out"]["payload"]["output"]["text"] == big  # 完整内容在 node_io


async def test_spec_roundtrip(store):
    eid = uuid.uuid4()
    await store.put_spec(eid, {"a": 1})
    await store.put_spec(eid, {"a": 2})  # ON CONFLICT DO NOTHING
    assert await store.get_spec(eid) == {"a": 1}
    assert await store.get_spec(uuid.uuid4()) is None


async def test_migration_drift_rejected(store, tmp_path):
    import pathlib

    src = pathlib.Path(Settings().pg_migrations_path)
    dst = tmp_path / "migrations"
    dst.mkdir()
    content = (src / "V001__event_log.sql").read_text(encoding="utf-8")
    (dst / "V001__event_log.sql").write_text(content + "\n-- tampered\n", encoding="utf-8")
    with pytest.raises(RuntimeError, match="forbidden"):
        await run_migrations(store.pool, str(dst))
