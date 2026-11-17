"""事件门面：强制「写事件 → 应用状态 → 对外可见」顺序（§6.3）。"""

from __future__ import annotations

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
            apply(event)  # 先应用状态
        await self._publisher.publish(event.to_dict())  # 再对外可见
        return event
