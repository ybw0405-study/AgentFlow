"""执行租约（§8.5）：Redis SET NX PX + 续约。

定位：租约只做冲突降级，正确性由 aggregate_version 乐观锁兜底。
GET+SET 非原子是可接受的——双写者最终会被唯一索引撞停。
"""

from __future__ import annotations

import logging

import redis.asyncio as aioredis

logger = logging.getLogger(__name__)


class Lease:
    def __init__(self, redis: aioredis.Redis, key: str, ttl_ms: int, owner: str) -> None:
        self._r = redis
        self.key = key
        self.ttl_ms = ttl_ms
        self.owner = owner

    async def acquire(self) -> bool:
        return bool(await self._r.set(self.key, self.owner, px=self.ttl_ms, nx=True))

    async def renew(self) -> bool:
        value = await self._r.get(self.key)
        if value != self.owner:
            return False
        return bool(await self._r.set(self.key, self.owner, px=self.ttl_ms, xx=True))

    async def held_by_me(self) -> bool:
        return (await self._r.get(self.key)) == self.owner

    async def release(self) -> None:
        if await self.held_by_me():
            await self._r.delete(self.key)
