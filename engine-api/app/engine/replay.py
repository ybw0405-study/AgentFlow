"""确定性重放：按 aggregate_version 折叠事件，重建内存状态（§8.2）。

重放只读事件，不重新执行任何已成功节点、不重发任何 Command。
溢出事件的 payload 只是引用，重建聚合前先从 node_io 回填完整内容。
"""

from __future__ import annotations

import dataclasses
import uuid
from typing import Any

from ..events.types import Event
from .state import ExecutionState


def _is_overflow(event: Event) -> bool:
    return isinstance(event.payload, dict) and bool(event.payload.get("_overflow"))


async def resolve_overflow_payloads(
    store: Any, execution_id: uuid.UUID, events: list[Event]
) -> dict[int, dict[str, Any]]:
    """按 node_io(OUT) 回填溢出事件的完整 payload（key = aggregate_version）。

    溢出时事件与 node_io 在同一 PG 事务写入，OUT 保存的正是被替换掉的原始
    payload；崩溃重放据此复原 NODE_COMPLETED.output，避免下游引用断链。
    """
    out: dict[int, dict[str, Any]] = {}
    for event in events:
        if not _is_overflow(event) or event.node_key is None:
            continue
        io = await store.get_node_io(execution_id, event.node_key)
        spilled = io.get("out") if isinstance(io, dict) else None
        payload = spilled.get("payload") if isinstance(spilled, dict) else None
        if isinstance(payload, dict):
            out[event.aggregate_version] = payload
    return out


async def rebuild_state_hydrated(
    execution_id: uuid.UUID,
    events: list[Event],
    store: Any,
    *,
    seed_fallback: str = "",
) -> ExecutionState:
    """恢复入口：先回填溢出事件，再折叠状态（同步 rebuild_state 的 async 包装）。"""
    overflow = await resolve_overflow_payloads(store, execution_id, events)
    return rebuild_state(
        execution_id, events, seed_fallback=seed_fallback, overflow_payloads=overflow
    )


def rebuild_state(
    execution_id: uuid.UUID,
    events: list[Event],
    *,
    seed_fallback: str = "",
    overflow_payloads: dict[int, dict[str, Any]] | None = None,
) -> ExecutionState:
    state = ExecutionState(execution_id=execution_id, seed=seed_fallback)
    for event in sorted(events, key=lambda e: e.aggregate_version):
        if overflow_payloads and _is_overflow(event):
            full = overflow_payloads.get(event.aggregate_version)
            if full is not None:
                event = dataclasses.replace(event, payload=full)
        state.apply(event)
    return state
