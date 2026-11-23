"""单元测试夹具：确定性模型 provider 与离线工具连接器。

产品侧不装配这两个实现：`app/main.py` 只注册真实 provider，tool 节点只走
connector-service。这里保留它们是为了让重放、恢复、重试等语义可以在无外网、
无密钥的机器上稳定验证——被测的是引擎的调度与幂等逻辑，不是模型输出质量。

- `FakeLLMProvider`：同 (seed, prompt, model) 产出同一文本与同一 token 计数。
- `FakeToolConnector`：副作用计数落 Redis 并按 callId 去重，用于「恰好一次」验证。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import time
from typing import Any

import redis.asyncio as aioredis

from app.commands.base import CommandResult
from app.errors import PermanentNodeError, TransientNodeError
from app.governance.tokenmeter import estimate_tokens


class FakeLLMProvider:
    name = "fake"

    async def complete(
        self,
        *,
        model: str,
        prompt: str,
        system: str | None = None,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        output_mode: str = "text",
        output_schema: dict[str, Any] | None = None,
        seed: str,
        trace_id: str,
        call_id: str = "",
        space_id: str = "",
    ) -> CommandResult:
        started = time.monotonic()
        digest = hashlib.sha256(f"{seed}|{prompt}".encode()).hexdigest()
        head = prompt[:160].replace("\n", " ")
        text = f"[fake-llm:{model} #{digest[:8]}] 确定性测试输出。输入摘要：{head}"
        text = text[: max_tokens * 4]
        parsed: dict[str, Any] | None = None
        if output_mode == "json":
            parsed = {"text": text, "confidence": 0.9}
            text = json.dumps(parsed, ensure_ascii=False)
        prompt_tokens = estimate_tokens(prompt + (system or ""))
        completion_tokens = estimate_tokens(text)
        return CommandResult(
            ok=True,
            data={"text": text, "parsed": parsed},
            token_usage=prompt_tokens + completion_tokens,
            latency_ms=int((time.monotonic() - started) * 1000),
            extra={
                "usage": {
                    "prompt": prompt_tokens,
                    "completion": completion_tokens,
                    "total": prompt_tokens + completion_tokens,
                }
            },
        )


class FakeToolConnector:
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
            # 挂在缓存写入之前：睡到一半被 kill = 副作用未发生
            await asyncio.sleep(int(params.get("sleepMs", 1000)) / 1000)
        cache_key = f"af:fakecall:{call_id}"
        cached = await self._r.get(cache_key)
        if cached is not None:
            data = json.loads(cached)
            data["_replayed"] = True
            return CommandResult(
                ok=True, data=data, latency_ms=int((time.monotonic() - started) * 1000)
            )

        result = self._execute(operation, params)
        payload = json.dumps(result, ensure_ascii=False)
        first = await self._r.set(cache_key, payload, nx=True)
        if first:
            await self._r.incr(f"af:fakecount:{self.connector_id}:{operation}")
            await self._r.sadd(f"af:fakecalls:{self.connector_id}", call_id)
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
                        "snippet": f"测试语料片段 {i}：包含查询关键词的上下文。",
                        "score": round(0.95 - i * 0.07, 2),
                    }
                    for i in range(3)
                ],
                "query": q,
            }
        if operation == "echo":
            return {"echo": params}
        if operation == "flaky":
            raise TransientNodeError("TOOL-FLAKY", "flaky transient failure")
        raise PermanentNodeError("TOOL-OP-001", f"unknown test operation '{operation}'")


class FakeMcpConnector:
    """mcp 节点评测桩：按 mcp_method 回固定内容，callId 去重骨架与 FakeToolConnector 同。"""

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
        mcp_method: str | None = None,
    ) -> CommandResult:
        started = time.monotonic()
        cache_key = f"af:fakecall:{call_id}"
        cached = await self._r.get(cache_key)
        if cached is not None:
            data = json.loads(cached)
            data["_replayed"] = True
            return CommandResult(
                ok=True, data=data, latency_ms=int((time.monotonic() - started) * 1000)
            )
        result = self._execute(mcp_method, params)
        payload = json.dumps(result, ensure_ascii=False)
        first = await self._r.set(cache_key, payload, nx=True)
        if first:
            await self._r.incr(f"af:fakecount:{self.connector_id}:{mcp_method}")
        else:
            result = json.loads(await self._r.get(cache_key) or "{}")
            result["_deduplicated"] = True
        return CommandResult(
            ok=True, data=result, latency_ms=int((time.monotonic() - started) * 1000)
        )

    def _execute(self, mcp_method: str | None, params: dict[str, Any]) -> dict[str, Any]:
        if mcp_method == "resources/read":
            uri = str(params.get("uri", ""))
            return {
                "contents": [{"uri": uri, "type": "text", "text": f"资源正文 {uri}"}],
                "text": f"资源正文 {uri}",
            }
        if mcp_method == "prompts/get":
            who = str(params.get("arguments", {}).get("who", ""))
            return {
                "messages": [
                    {"role": "user", "content": {"type": "text", "text": f"hello {who}"}}
                ],
                "text": f"user: hello {who}",
            }
        raise PermanentNodeError("MCP-OP-001", f"unknown test mcp method '{mcp_method}'")


__all__ = ["FakeLLMProvider", "FakeMcpConnector", "FakeToolConnector"]
