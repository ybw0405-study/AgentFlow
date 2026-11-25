"""PostgreSQL append-only 事件存储。

纪律（§6.3）：写事件 → 应用状态 → 对外可见；禁止先改状态再补事件。
并发控制（§8.2.4）：aggregate_version 乐观并发，冲突重读重试 ≤3 次。
溢出：NodeCompleted payload 超 256KB 时完整内容落 node_io，事件仅存引用+digest。
"""

from __future__ import annotations

import hashlib
import json
import uuid
from typing import Any

import asyncpg

from ..errors import EventConcurrencyError, EventTerminalError
from .types import TERMINAL_EVENTS, Event, EventType


class EventStore:
    def __init__(self, dsn: str, *, payload_max_bytes: int = 256 * 1024) -> None:
        self._dsn = dsn
        self._max_bytes = payload_max_bytes
        self._pool: asyncpg.Pool | None = None

    async def connect(self) -> None:
        self._pool = await asyncpg.create_pool(self._dsn, min_size=1, max_size=10)

    async def close(self) -> None:
        if self._pool:
            await self._pool.close()
            self._pool = None

    async def ping(self) -> None:
        """readiness 探针：短超时 SELECT 1 确认连接池可用。"""
        pool = self._require_pool()
        async with pool.acquire() as conn:
            await conn.fetchval("SELECT 1")

    async def append(
        self,
        execution_id: uuid.UUID,
        event_type: str,
        payload: dict[str, Any],
        *,
        node_key: str | None = None,
        node_io_spill: dict[str, Any] | None = None,
    ) -> Event:
        etype = EventType(event_type)
        raw = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        if len(raw) > self._max_bytes and node_key is not None:
            # 溢出：完整内容落 node_io(OUT)，事件存引用
            digest = hashlib.sha256(raw).hexdigest()
            node_io_spill = payload
            payload = {"_overflow": True, "digest": digest, "size": len(raw)}
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        terminal = etype in TERMINAL_EVENTS

        for _ in range(3):
            pool = self._require_pool()
            try:
                async with pool.acquire() as conn, conn.transaction():
                    # 同执行的写入者串行化；aggregate_version 唯一索引仍兜底（§8.2.4）
                    await conn.execute(
                        "SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))",
                        str(execution_id),
                    )
                    # 终态不可逆：advisory lock 已把同一执行的写入者串行化，
                    # 读到 TERMINAL 即说明执行已结束。迟到的非终态事件一律拒绝，
                    # 否则下面的 upsert 会把 tracker 回写成 OPEN，恢复扫描又捞出去续跑。
                    tracker = await conn.fetchrow(
                        "SELECT status FROM execution_tracker WHERE execution_id = $1",
                        execution_id,
                    )
                    if tracker is not None and tracker["status"] == "TERMINAL" and not terminal:
                        raise EventTerminalError(
                            f"execution {execution_id} already terminal; rejecting {etype}"
                        )
                    row = await conn.fetchrow(
                        "SELECT COALESCE(MAX(aggregate_version), 0) + 1 AS v "
                        "FROM execution_event WHERE execution_id = $1",
                        execution_id,
                    )
                    version: int = row["v"]
                    rec = await conn.fetchrow(
                        "INSERT INTO execution_event"
                        " (execution_id, event_type, node_key, payload, aggregate_version)"
                        " VALUES ($1, $2, $3, $4::jsonb, $5) RETURNING seq, created_at",
                        execution_id,
                        str(etype),
                        node_key,
                        raw.decode("utf-8"),
                        version,
                    )
                    if node_io_spill is not None and node_key is not None:
                        await self._upsert_node_io(
                            conn, execution_id, node_key, "OUT", node_io_spill
                        )
                    await conn.execute(
                        "INSERT INTO execution_tracker (execution_id, status, last_seq)"
                        " VALUES ($1, $2, $3)"
                        " ON CONFLICT (execution_id) DO UPDATE"
                        " SET status = CASE WHEN execution_tracker.status = 'TERMINAL'"
                        " THEN 'TERMINAL' ELSE EXCLUDED.status END,"
                        " last_seq = EXCLUDED.last_seq,"
                        " updated_at = now()",
                        execution_id,
                        "TERMINAL" if terminal else "OPEN",
                        rec["seq"],
                    )
                    return Event(
                        seq=rec["seq"],
                        execution_id=execution_id,
                        event_type=etype,
                        node_key=node_key,
                        payload=payload,
                        aggregate_version=version,
                        created_at=rec["created_at"],
                    )
            except asyncpg.UniqueViolationError:
                continue  # 并发追加冲突：重读版本号重试（§8.2.4）
        raise EventConcurrencyError(f"append conflict for execution {execution_id}")

    async def read_all(self, execution_id: uuid.UUID) -> list[Event]:
        pool = self._require_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT seq, execution_id, event_type, node_key, payload, aggregate_version,"
                " created_at"
                " FROM execution_event WHERE execution_id = $1 ORDER BY aggregate_version",
                execution_id,
            )
        return [self._row_to_event(r) for r in rows]

    async def tail(self, after_seq: int, limit: int, *, space_id: str | None = None) -> list[Event]:
        """全局事件尾读。space_id 非空时把空间谓词下推到 SQL。

        execution_spec.spec 是提交的 ExecuteRequest 快照（canonical_dump，
        camelCase 键 spaceId）。用 EXISTS 关联而不是给事件表加列：事件表是
        append-only 且已有数据无法回填，快照是唯一可追溯的空间归属来源。
        fail-closed：没有 spec 的事件（无法证明空间归属）不出现在任何按空间
        的读取里；space_id 为 None 时保持全局语义，供系统级 EventProjector 使用。
        """
        pool = self._require_pool()
        if space_id is None:
            sql = (
                "SELECT seq, execution_id, event_type, node_key, payload,"
                " aggregate_version, created_at"
                " FROM execution_event WHERE seq > $1 ORDER BY seq LIMIT $2"
            )
            args: tuple = (after_seq, limit)
        else:
            sql = (
                "SELECT e.seq, e.execution_id, e.event_type, e.node_key, e.payload,"
                " e.aggregate_version, e.created_at"
                " FROM execution_event e"
                " WHERE e.seq > $1"
                " AND EXISTS (SELECT 1 FROM execution_spec s"
                " WHERE s.execution_id = e.execution_id"
                " AND s.spec->>'spaceId' = $2)"
                " ORDER BY e.seq LIMIT $3"
            )
            args = (after_seq, space_id, limit)
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, *args)
        return [self._row_to_event(r) for r in rows]

    async def tail_for_execution(
        self, execution_id: uuid.UUID, after_seq: int, limit: int
    ) -> list[Event]:
        pool = self._require_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT seq, execution_id, event_type, node_key, payload,"
                " aggregate_version, created_at"
                " FROM execution_event WHERE execution_id = $1 AND seq > $2"
                " ORDER BY seq LIMIT $3",
                execution_id,
                after_seq,
                limit,
            )
        return [self._row_to_event(r) for r in rows]

    async def open_executions(self, limit: int = 200) -> list[uuid.UUID]:
        """恢复扫描：所有未达终态的执行。

        带上限：一次故障可能遗留上万条 OPEN，全量拉回内存既慢又会挤掉正常调度；
        按最近更新时间取前 N 条，剩余部分由下一轮扫描继续。
        """
        pool = self._require_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT execution_id FROM execution_tracker WHERE status = 'OPEN'"
                " ORDER BY updated_at LIMIT $1",
                limit,
            )
        return [r["execution_id"] for r in rows]

    async def suspended_gates(self, limit: int = 500) -> list[tuple[uuid.UUID, str, dict]]:
        """仍在等待裁决的 gate（超时扫描用）。

        以事件为唯一真相：有 GateSuspended 且其后没有同节点 GateResolved，
        且执行本身还没落终态。按 seq 升序取最早的一批，避免长期挂起的 gate
        因为新事件不断涌入而永远排在后面。
        """
        pool = self._require_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT s.execution_id, s.node_key, s.payload"
                " FROM execution_event s"
                " WHERE s.event_type = 'GateSuspended'"
                " AND NOT EXISTS ("
                " SELECT 1 FROM execution_event r"
                " WHERE r.execution_id = s.execution_id"
                " AND r.event_type = 'GateResolved'"
                " AND r.node_key = s.node_key"
                " AND r.aggregate_version > s.aggregate_version)"
                " AND NOT EXISTS ("
                " SELECT 1 FROM execution_event t"
                " WHERE t.execution_id = s.execution_id"
                " AND t.event_type IN ('ExecutionCompleted','ExecutionFailed',"
                " 'ExecutionTimedOut','ExecutionCancelled'))"
                " ORDER BY s.seq LIMIT $1",
                limit,
            )
        return [
            (r["execution_id"], r["node_key"], json.loads(r["payload"]))
            if isinstance(r["payload"], str)
            else (r["execution_id"], r["node_key"], r["payload"])
            for r in rows
        ]

    def _require_pool(self) -> asyncpg.Pool:
        if self._pool is None:
            raise RuntimeError("EventStore is not connected")
        return self._pool

    @property
    def pool(self) -> asyncpg.Pool:
        return self._require_pool()

    async def put_spec(self, execution_id: uuid.UUID, spec: dict[str, Any]) -> None:
        raw = json.dumps(spec, ensure_ascii=False, default=str)
        pool = self._require_pool()
        async with pool.acquire() as conn:
            await conn.execute(
                "INSERT INTO execution_spec (execution_id, spec) VALUES ($1, $2::jsonb)"
                " ON CONFLICT (execution_id) DO NOTHING",
                execution_id,
                raw,
            )

    async def get_spec(self, execution_id: uuid.UUID) -> dict[str, Any] | None:
        pool = self._require_pool()
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                "SELECT spec FROM execution_spec WHERE execution_id = $1", execution_id
            )
        return json.loads(row["spec"]) if row else None

    async def delete_spec(self, execution_id: uuid.UUID) -> None:
        """试跑收口后清理 spec。只在 tracker 已落终态之后调用，
        否则恢复扫描会丢失 preview 标记、把调试执行当成崩溃业务执行续跑。
        """
        pool = self._require_pool()
        async with pool.acquire() as conn:
            await conn.execute("DELETE FROM execution_spec WHERE execution_id = $1", execution_id)

    async def get_node_io(self, execution_id: uuid.UUID, node_key: str) -> dict[str, Any]:
        pool = self._require_pool()
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                "SELECT direction, payload, content_hash FROM node_io"
                " WHERE execution_id = $1 AND node_key = $2",
                execution_id,
                node_key,
            )
        out: dict[str, Any] = {"in": None, "out": None}
        for r in rows:
            key = "in" if r["direction"] == "IN" else "out"
            out[key] = {"payload": json.loads(r["payload"]), "contentHash": r["content_hash"]}
        return out

    async def put_node_io(
        self,
        execution_id: uuid.UUID,
        node_key: str,
        direction: str,
        payload: dict[str, Any],
    ) -> None:
        pool = self._require_pool()
        async with pool.acquire() as conn:
            await self._upsert_node_io(conn, execution_id, node_key, direction, payload)

    @staticmethod
    async def _upsert_node_io(
        conn: asyncpg.Connection,
        execution_id: uuid.UUID,
        node_key: str,
        direction: str,
        payload: dict[str, Any],
    ) -> None:
        raw = json.dumps(payload, ensure_ascii=False, default=str)
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        await conn.execute(
            "INSERT INTO node_io (execution_id, node_key, direction, payload, content_hash)"
            " VALUES ($1, $2, $3, $4::jsonb, $5)"
            " ON CONFLICT (execution_id, node_key, direction) DO UPDATE"
            " SET payload = EXCLUDED.payload, content_hash = EXCLUDED.content_hash",
            execution_id,
            node_key,
            direction,
            raw,
            digest,
        )

    @staticmethod
    def _row_to_event(r: asyncpg.Record) -> Event:
        return Event(
            seq=r["seq"],
            execution_id=r["execution_id"],
            event_type=EventType(r["event_type"]),
            node_key=r["node_key"],
            payload=json.loads(r["payload"]),
            aggregate_version=r["aggregate_version"],
            created_at=r["created_at"],
        )
