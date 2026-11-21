"""执行租约（§8.5）：Redis SET NX PX + 续约。

定位：租约只做冲突降级，正确性由 aggregate_version 乐观锁兜底。
renew / release 用 WATCH/MULTI 把「比对 owner + 改写」变成原子操作：原实现
GET 与 SET 之间若恰好过期并被新 worker 抢占，SET xx 会把新持有者的租约覆盖
成自己的，双方都自认持有（CON-3）。WATCH/MULTI 在冲突时重试，兼容 fakeredis
且不依赖 Lua（与 budget.py 同策略）。acquire 本身已是单条 SET NX PX，天然原子。
"""

from __future__ import annotations

import logging

import redis.asyncio as aioredis
from redis.exceptions import WatchError

logger = logging.getLogger(__name__)

_RENEW_RETRIES = 5


class Lease:
    def __init__(self, redis: aioredis.Redis, key: str, ttl_ms: int, owner: str) -> None:
        self._r = redis
        self.key = key
        self.ttl_ms = ttl_ms
        self.owner = owner

    async def acquire(self) -> bool:
        return bool(await self._r.set(self.key, self.owner, px=self.ttl_ms, nx=True))

    async def renew(self) -> bool:
        """仅当租约仍属于我时续期；owner 被改或冲突耗尽都返回 False。"""
        async with self._r.pipeline() as pipe:
            for _ in range(_RENEW_RETRIES):
                try:
                    await pipe.watch(self.key)
                    if await pipe.get(self.key) != self.owner:
                        await pipe.unwatch()
                        return False
                    pipe.multi()
                    pipe.pexpire(self.key, self.ttl_ms)
                    result = await pipe.execute()
                    return bool(result[0])
                except WatchError:
                    continue
        return False

    async def held_by_me(self) -> bool:
        return (await self._r.get(self.key)) == self.owner

    async def release(self) -> None:
        """仅删除仍属于我的租约，绝不删他人抢占后的新租约。"""
        async with self._r.pipeline() as pipe:
            for _ in range(_RENEW_RETRIES):
                try:
                    await pipe.watch(self.key)
                    if await pipe.get(self.key) != self.owner:
                        await pipe.unwatch()
                        return
                    pipe.multi()
                    pipe.delete(self.key)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        logger.warning("lease release exhausted retries key=%s owner=%s", self.key, self.owner)
