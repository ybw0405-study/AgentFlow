"""事件门面：强制「写事件 → 应用状态 → 对外可见」顺序（§6.3）。"""

from __future__ import annotations

import dataclasses
import uuid
from collections.abc import Callable
from typing import Any

from .publisher import EventPublisher
from .store import EventStore
from .types import Event

ApplyFn = Callable[[Event], None]


class EventLog:
    def __init__(self, store: EventStore, publisher: EventPublisher) -> None:
        self._store = store
        self._publisher = publisher

    async def append(
        self,
        execution_id: uuid.UUID,
        event_type: str,
        payload: dict[str, Any],
        *,
        node_key: str | None = None,
        apply: ApplyFn | None = None,
    ) -> Event:
        event = await self._store.append(execution_id, event_type, payload, node_key=node_key)
        if apply is not None:
            # 溢出（D5）时 store 落库的是 {"_overflow": ...} 引用、完整内容转存
            # node_io(OUT)。内存聚合必须按原始 payload 折叠，否则 NODE_COMPLETED
            # 的 output 变 None，下游 {{ nodes.xxx.output }} 当场断链。对外发布仍
            # 用落库事件形态（引用），订阅方按 _overflow 自行取 node_io。
            if isinstance(event.payload, dict) and event.payload.get("_overflow"):
                apply(dataclasses.replace(event, payload=payload))
            else:
                apply(event)
        await self._publisher.publish(event.to_dict())
        return event
