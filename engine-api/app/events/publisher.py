"""事件实时推送：Redis pub/sub 频道 af:exec-events（at-most-once，D2）。

订阅方断线后通过 /internal/events?afterSeq= 追平，因此发布失败只降级不致命。
"""

from __future__ import annotations

import json
import logging

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)

CHANNEL = "af:exec-events"


class EventPublisher:
    def __init__(self, redis: aioredis.Redis) -> None:
        self._redis = redis

    async def publish(self, event_dict: dict) -> None:
        try:
            await self._redis.publish(CHANNEL, json.dumps(event_dict, ensure_ascii=False))
        except Exception:
            logger.warning(
                "event publish degraded (SSE clients will catch up via tail)", exc_info=True
            )
