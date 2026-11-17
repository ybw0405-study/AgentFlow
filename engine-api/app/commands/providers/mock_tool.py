"""内置 mock 连接器（D3）：无凭据、无外网，副作用计数落 Redis 并按 callId 去重。

P0–P1 用于演示与「恰好一次」验证；P2 由 connector-service 承接真实工具。

支持的 connectorId / operation：
- mock.search / search(q)              → 固定语料检索结果
- mock.echo   / echo(...)              → 回显参数
- mock.flaky  / flaky(failTimes)       → 前 N 次瞬时失败（重试测试装置）
"""

from __future__ import annotations

import asyncio
import json
import time
from typing import Any

import redis.asyncio as aioredis

from ...errors import PermanentNodeError, TransientNodeError
from ..base import CommandResult


class MockToolConnector:
    def __init__(self, redis: aioredis.Redis, connector_id: str) -> None:
        self._r = redis
        self.connector_id = connector_id

    async def invoke(
        self,
        *,
        call_id: str,
        operation: str,
        params: dict[str, Any],
        timeout_ms: int,
        trace_id: str,
        space_id: str = "",
    ) -> CommandResult:
        started = time.monotonic()
        if operation == "sleep":
            # 测试/演示装置：挂在缓存写入之前——睡到一半被 kill = 副作用未发生
            sleep_ms = int(params.get("sleepMs", 1000))
            await asyncio.sleep(sleep_ms / 1000)
        cache_key = f"af:mockcall:{call_id}"
        cached = await self._r.get(cache_key)
        if cached is not None:
            data = json.loads(cached)
            data["_replayed"] = True  # 观测用：本次未产生新副作用
            return CommandResult(
                ok=True, data=data, latency_ms=int((time.monotonic() - started) * 1000)
            )

        result = self._execute(operation, params)  # 可能抛 TransientNodeError
        payload = json.dumps(result, ensure_ascii=False)
        # SET NX：只有首次调用计入副作用计数；崩溃重发同 callId 返回缓存结果
        first = await self._r.set(cache_key, payload, nx=True)
        if first:
            await self._r.incr(f"af:mockcount:{self.connector_id}:{operation}")
            await self._r.sadd(f"af:mockcalls:{self.connector_id}", call_id)
        else:
            result = json.loads(await self._r.get(cache_key) or "{}")
            result["_deduplicated"] = True
        return CommandResult(
            ok=True, data=result, latency_ms=int((time.monotonic() - started) * 1000)
        )

    def _execute(self, operation: str, params: dict[str, Any]) -> dict[str, Any]:
        if operation == "sleep":
            return {"sleptMs": int(params.get("sleepMs", 1000))}
        if operation == "search":
            q = str(params.get("q", ""))
            return {
                "results": [
                    {
                        "title": f"与「{q[:30]}」相关的文档 {i}",
                        "snippet": f"mock 语料片段 {i}：包含查询关键词的上下文。",
                        "score": round(0.95 - i * 0.07, 2),
                    }
                    for i in range(3)
                ],
                "query": q,
            }
        if operation == "echo":
            return {"echo": params}
        if operation == "flaky":
            raise TransientNodeError("TOOL-FLAKY", "mock flaky transient failure")
        raise PermanentNodeError("TOOL-OP-001", f"unknown mock operation '{operation}'")
