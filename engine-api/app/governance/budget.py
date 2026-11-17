"""预算账本（Redis）：预算硬停止机制 D11。

- 发出 Command 前 reserve：remaining<=0 → -1（预算耗尽，禁止发出）；
  否则 grant=min(requested, remaining) 并把该 Command 的 maxTokens 钳到 grant。
- 完成后 settle 回冲未用部分。
- 用 WATCH/MULTI 乐观事务保证同执行内并行节点的原子性（兼容 fakeredis，不依赖 Lua）。
"""

from __future__ import annotations

import uuid

import redis.asyncio as aioredis
from redis.exceptions import WatchError


class BudgetLedger:
    def __init__(self, redis: aioredis.Redis, prefix: str = "af:budget") -> None:
        self._r = redis
        self._prefix = prefix

    def _key(self, execution_id: uuid.UUID | str) -> str:
        return f"{self._prefix}:{execution_id}"

    async def reserve(self, execution_id: uuid.UUID | str, budget: int, requested: int) -> int:
        """返回实际预占额度；-1 表示预算耗尽。"""
        key = self._key(execution_id)
        async with self._r.pipeline() as pipe:
            for _ in range(5):
                try:
                    await pipe.watch(key)
                    used = int(await pipe.get(key) or 0)
                    remaining = budget - used
                    if remaining <= 0:
                        await pipe.unwatch()
                        return -1
                    grant = min(requested, remaining)
                    pipe.multi()
                    pipe.set(key, used + grant)
                    await pipe.execute()
                    return grant
                except WatchError:
                    continue
        return -1  # 冲突过多时按耗尽处理（保守方向，宁停不超）

    async def settle(self, execution_id: uuid.UUID | str, grant: int, actual: int) -> None:
        refund = grant - actual
        if refund > 0:
            await self._r.decrby(self._key(execution_id), refund)

    async def used(self, execution_id: uuid.UUID | str) -> int:
        return int(await self._r.get(self._key(execution_id)) or 0)

    async def reconcile(self, execution_id: uuid.UUID | str, used: int) -> int:
        """以事件流的 tokenUsed 为准校准 Redis 预占（§5.6 对账）。

        崩溃可能发生在「CommandCompleted 已落库、settle 未执行」之间，导致预占多算。
        恢复与 checkpoint 时把键重设为事件流汇总额，回收泄漏。返回回冲额度
        （>0 表示收回了未结算的预占；0 表示一致）。
        """
        key = self._key(execution_id)
        current = await self._r.get(key)
        if current is None:
            if used > 0:
                await self._r.set(key, used)
            return 0
        reserved = int(current)
        if reserved == used:
            return 0
        await self._r.set(key, used)
        return reserved - used
