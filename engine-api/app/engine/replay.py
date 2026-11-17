"""确定性重放：按 aggregate_version 折叠事件，重建内存状态（§8.2）。

重放只读事件，不重新执行任何已成功节点、不重发任何 Command。
"""

from __future__ import annotations

import uuid

from ..events.types import Event
from .state import ExecutionState


def rebuild_state(
    execution_id: uuid.UUID, events: list[Event], *, seed_fallback: str = ""
) -> ExecutionState:
    state = ExecutionState(execution_id=execution_id, seed=seed_fallback)
    for event in sorted(events, key=lambda e: e.aggregate_version):
        state.apply(event)
    return state
