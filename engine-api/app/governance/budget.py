"""预算账本（Redis）：预算硬停止机制 D11。

- 发出 Command 前 reserve：remaining<=0 → -1（预算耗尽，禁止发出）；
  否则 grant=min(requested, remaining) 并把该 Command 的 maxTokens 钳到 grant。
- 完成后 settle 回冲未用部分。
- 用 WATCH/MULTI 乐观事务保证同执行内并行节点的原子性（兼容 fakeredis，不依赖 Lua）。

对账纪律（§5.6）：事件流只记录「已完成节点」的实际用量，Redis 键里还包含
「已 reserve、未 settle」的在途预占。checkpoint 按事件计数触发，完全可能落在
reserve 与 NODE_COMPLETED 之间；若这时用事件流汇总额无条件覆盖 Redis，在途
预占就被抹掉，紧接着 settle 再回冲一次，键可被减成负数，后续 reserve 把剩余
额度算大 —— 对账动作反倒制造了超发。因此用 inflight 计数标记在途：只要还有
未结算的预占，reconcile 就什么都不做；只有接管/恢复（旧执行器已不在，重放会
重新 reserve）才显式 reset_inflight 清零，再按事件流重算。
"""

from __future__ import annotations

import logging
import uuid

import redis.asyncio as aioredis
from redis.exceptions import WatchError

logger = logging.getLogger(__name__)

INFLIGHT_TTL_SECONDS = 7 * 24 * 3600
"""在途计数的兜底过期时间：正常由 settle 递减，异常残留时不要让 key 永久驻留。"""

BUDGET_KEY_TTL_SECONDS = 7 * 24 * 3600
"""预算主键的兜底过期时间：执行终态后该键已无消费者，避免每个执行留下永久键。

与 INFLIGHT_TTL 同为 7 天：远大于任何执行的合法窗口，正常执行不会活到 TTL 到期；
只在「执行永远没走到终态」的异常残留下才由它兜底回收。每次 reserve/settle 都会
刷新，等价于滑动过期——长执行只要还在推进就不会因 TTL 到期而丢预算。
"""


class BudgetLedger:
    def __init__(self, redis: aioredis.Redis, prefix: str = "af:budget") -> None:
        self._r = redis
        self._prefix = prefix

    def _key(self, execution_id: uuid.UUID | str) -> str:
        return f"{self._prefix}:{execution_id}"

    def _inflight_key(self, execution_id: uuid.UUID | str) -> str:
        return f"{self._prefix}:{execution_id}:inflight"

    async def exists(self, execution_id: uuid.UUID | str) -> bool:
        """预算键是否还在。Redis 整体丢失后，恢复扫描据此判断是否需要重建。"""
        return bool(await self._r.exists(self._key(execution_id)))

    async def reserve(self, execution_id: uuid.UUID | str, budget: int, requested: int) -> int:
        """返回实际预占额度；-1 表示预算耗尽。成功预占同时登记一笔在途。"""
        key = self._key(execution_id)
        inflight = self._inflight_key(execution_id)
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
                    pipe.expire(key, BUDGET_KEY_TTL_SECONDS)
                    pipe.incr(inflight)
                    pipe.expire(inflight, INFLIGHT_TTL_SECONDS)
                    await pipe.execute()
                    return grant
                except WatchError:
                    continue
        return -1 # 冲突过多时按耗尽处理（保守方向，宁停不超）

    async def settle(self, execution_id: uuid.UUID | str, grant: int, actual: int) -> None:
        """结算一笔预占：回冲未用额度，并注销对应的在途登记。

        回冲与在途递减放在同一个 MULTI 里；WATCH 在途键保证并发结算不会把它
        减成负数（负数会让后续 reconcile 误以为没有在途预占）。
        """
        refund = grant - actual
        key = self._key(execution_id)
        inflight = self._inflight_key(execution_id)
        async with self._r.pipeline() as pipe:
            for _ in range(5):
                try:
                    await pipe.watch(inflight)
                    pending = int(await pipe.get(inflight) or 0)
                    pipe.multi()
                    if refund > 0:
                        pipe.decrby(key, refund)
                    elif refund < 0:
                        # 上游实际用量超过预占时追扣：静默放过会让键系统性低估，
                        # 后续节点继续按虚高余额预占，tokenUsed <= tokenBudget 失守（CON-1）。
                        pipe.incrby(key, -refund)
                    if pending > 0:
                        pipe.decr(inflight)
                    await pipe.execute()
                    return
                except WatchError:
                    continue
        logger.warning("budget settle exhausted retries execution=%s", execution_id)

    async def used(self, execution_id: uuid.UUID | str) -> int:
        return int(await self._r.get(self._key(execution_id)) or 0)

    async def reconcile(
        self, execution_id: uuid.UUID | str, used: int, *, reset_inflight: bool = False
    ) -> int:
        """以事件流的 tokenUsed 为准校准 Redis 预占（§5.6 对账）。

        checkpoint 与恢复共用这一入口，但语义不同：

        - checkpoint（默认）：只要还有在途预占就直接跳过。事件流汇总额不含
          在途部分，此时覆盖会抹掉预占并导致超发；
        - 恢复/接管（reset_inflight=True）：旧执行器已不在，未完成节点重放时
          会重新 reserve，因此先清空在途计数，再把键重设为事件流汇总额，
          回收「COMMAND_COMPLETED 已落、settle 未执行」的泄漏。

        返回回冲额度（>0 表示收回了未结算的预占；0 表示一致或跳过）。
        """
        key = self._key(execution_id)
        inflight = self._inflight_key(execution_id)
        if reset_inflight:
            await self._r.delete(inflight)
        pending = int(await self._r.get(inflight) or 0)
        if pending > 0:
            return 0

        async with self._r.pipeline() as pipe:
            for _ in range(5):
                try:
                    await pipe.watch(key)
                    current = await pipe.get(key)
                    if current is None:
                        if used > 0:
                            pipe.multi()
                            pipe.set(key, used)
                            pipe.expire(key, BUDGET_KEY_TTL_SECONDS)
                            await pipe.execute()
                        else:
                            await pipe.unwatch()
                        return 0
                    reserved = int(current)
                    if reserved == used:
                        await pipe.unwatch()
                        return 0
                    pipe.multi()
                    pipe.set(key, used)
                    pipe.expire(key, BUDGET_KEY_TTL_SECONDS)
                    await pipe.execute()
                    return reserved - used
                except WatchError:
                    continue
        return 0
