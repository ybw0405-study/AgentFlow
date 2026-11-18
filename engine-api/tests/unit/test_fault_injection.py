"""P1 故障注入：租约丢失 / 事件版本冲突 / 节点超时 / 重试 / 并行上限 / deadline。

对应提示词 §8 的错误处理与约束验收；全部在内存事件库 + fakeredis 上跑，无外部依赖。
"""

from __future__ import annotations

import asyncio

import pytest

from app.commands.base import CommandResult
from app.engine.requests import ExecuteRequest
from app.errors import EventConcurrencyError, TransientNodeError
from app.events.types import EventType
from tests.unit.conftest import InMemoryEventStore, make_execute_request, make_stack


def _dsl(nodes, edges, *, defaults=None, variables=None) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": variables or {},
        "defaults": defaults or {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": nodes,
        "edges": edges,
    }


def _req(dsl, **overrides) -> ExecuteRequest:
    return ExecuteRequest.model_validate(make_execute_request(dsl, **overrides))


def _sleep_node(key: str, sleep_ms: int, *, timeout_ms: int | None = None) -> dict:
    node = {
        "key": key,
        "type": "tool",
        "config": {
            "connectorId": "mock.echo",
            "operation": "sleep",
            "paramsMapping": {"sleepMs": str(sleep_ms)},
        },
    }
    if timeout_ms is not None:
        node["timeoutMs"] = timeout_ms
    return node


def _types(events) -> list[str]:
    return [e.event_type.value for e in events]


async def _await_task(dispatcher, execution_id, timeout_s: float = 5.0) -> None:
    task = dispatcher._tasks.get(execution_id)
    assert task is not None
    await asyncio.wait_for(task, timeout=timeout_s)


@pytest.mark.asyncio
async def test_lease_lost_stops_writing_events(settings, fake_redis, mem_store):
    """租约被抢占后，执行器必须在节点边界静默停止，不再写任何事件（§8.5）。"""
    dsl = _dsl(
        [
            _sleep_node("slow", 400),
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [{"from": "slow", "to": "end"}],
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)

    await asyncio.sleep(0.1)
    await fake_redis.delete(f"af:lease:{req.execution_id}")  # 模拟租约被另一 worker 抢占
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    types = _types(events)
    # 租约在节点边界丢失：进行中的 slow 节点允许落 NodeCompleted，但下游一律不再调度
    assert "ExecutionCompleted" not in types
    assert "ExecutionFailed" not in types  # 不写失败事件，等接管方续跑
    assert all(e.node_key != "end" for e in events)
    assert events[-1].event_type == EventType.NODE_COMPLETED
    assert events[-1].node_key == "slow"


@pytest.mark.asyncio
async def test_node_timeout_fails_execution(settings, fake_redis, mem_store):
    """单节点超时（NODE-TIMEOUT，瞬时类）在重试耗尽后必须使执行失败。"""
    dsl = _dsl(
        [
            _sleep_node("slow", 600, timeout_ms=150),
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [{"from": "slow", "to": "end"}],
        defaults={
            "timeoutMs": 5000,
            "maxParallelism": 4,
            "retry": {"maxAttempts": 1, "backoffMs": 100, "backoffMultiplier": 2},
        },
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    failed = [e for e in events if e.event_type == EventType.NODE_FAILED]
    assert failed, _types(events)
    assert failed[-1].payload["errorCode"] == "NODE-TIMEOUT"
    assert events[-1].event_type == EventType.EXECUTION_FAILED


class _FlakyOnceProvider:
    """首次调用抛瞬时错误，第二次成功——验证重试后成功路径。"""

    name = "mock"

    def __init__(self) -> None:
        self.calls = 0

    async def complete(
        self,
        *,
        model,
        prompt,
        system=None,
        temperature=0.7,
        max_tokens=1024,
        output_mode="text",
        output_schema=None,
        seed,
        trace_id,
        call_id="",
        space_id="",
    ):
        self.calls += 1
        if self.calls == 1:
            raise TransientNodeError("LLM-TRANSIENT", "boom")
        return CommandResult(ok=True, data={"text": "recovered"}, latency_ms=1, token_usage=1)


@pytest.mark.asyncio
async def test_transient_error_retried_then_succeeds(settings, fake_redis, mem_store):
    provider = _FlakyOnceProvider()
    dsl = _dsl(
        [
            {
                "key": "call",
                "type": "llm",
                "config": {"model": "mock-large", "promptTemplate": "hi", "maxTokens": 8},
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [{"from": "call", "to": "end"}],
        defaults={
            "timeoutMs": 5000,
            "maxParallelism": 4,
            "retry": {"maxAttempts": 3, "backoffMs": 100, "backoffMultiplier": 2},
        },
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store, llm_providers={"mock": provider})
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    completed = [
        e for e in events if e.event_type == EventType.NODE_COMPLETED and e.node_key == "call"
    ]
    assert completed and completed[-1].payload["attempt"] == 2
    assert any(e.event_type == EventType.NODE_RETRY_SCHEDULED for e in events)
    assert events[-1].event_type == EventType.EXECUTION_COMPLETED
    assert provider.calls == 2


class _AlwaysTransientProvider:
    name = "mock"

    def __init__(self) -> None:
        self.calls = 0

    async def complete(
        self,
        *,
        model,
        prompt,
        system=None,
        temperature=0.7,
        max_tokens=1024,
        output_mode="text",
        output_schema=None,
        seed,
        trace_id,
        call_id="",
        space_id="",
    ):
        self.calls += 1
        raise TransientNodeError("LLM-TRANSIENT", "always")


@pytest.mark.asyncio
async def test_transient_error_exhausts_retries(settings, fake_redis, mem_store):
    provider = _AlwaysTransientProvider()
    dsl = _dsl(
        [
            {
                "key": "call",
                "type": "llm",
                "config": {"model": "mock-large", "promptTemplate": "hi", "maxTokens": 8},
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [{"from": "call", "to": "end"}],
        defaults={
            "timeoutMs": 5000,
            "maxParallelism": 4,
            "retry": {"maxAttempts": 2, "backoffMs": 100, "backoffMultiplier": 2},
        },
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store, llm_providers={"mock": provider})
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    failed = [e for e in events if e.event_type == EventType.NODE_FAILED]
    assert failed and failed[-1].payload["attempt"] == 2
    assert provider.calls == 2  # 严格受 maxAttempts 约束
    assert events[-1].event_type == EventType.EXECUTION_FAILED


class _ConflictOnceStore(InMemoryEventStore):
    """在指定节点完成事件上抛一次乐观锁冲突，模拟双写者撞车（§8.2.4）。"""

    def __init__(self, conflict_node_key: str) -> None:
        super().__init__()
        self._conflict_node = conflict_node_key
        self._fired = False

    async def append(self, execution_id, event_type, payload, *, node_key=None, node_io_spill=None):
        if (
            not self._fired
            and node_key == self._conflict_node
            and str(event_type) == EventType.NODE_COMPLETED.value
        ):
            self._fired = True
            raise EventConcurrencyError("simulated append conflict")
        return await super().append(
            execution_id, event_type, payload, node_key=node_key, node_io_spill=node_io_spill
        )


@pytest.mark.asyncio
async def test_event_append_conflict_fails_execution(settings, fake_redis):
    store = _ConflictOnceStore("tag")
    dsl = _dsl(
        [
            {
                "key": "tag",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.ok", "template": "1"}]},
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [{"from": "tag", "to": "end"}],
        variables={
            "ok": {"type": "string", "required": False},
            "result": {"type": "string", "required": False},
        },
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, store)
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await store.read_all(req.execution_id)
    assert events, "事件流不应为空"
    assert events[-1].event_type == EventType.EXECUTION_FAILED
    assert events[-1].payload["errorCode"] == "EVT-CONCURRENCY-001"


class _ConcurrencyTrackingProvider:
    """记录并发峰值，验证 maxParallelism 硬约束。"""

    name = "mock"

    def __init__(self, delay_s: float = 0.1) -> None:
        self.delay_s = delay_s
        self.current = 0
        self.peak = 0

    async def complete(
        self,
        *,
        model,
        prompt,
        system=None,
        temperature=0.7,
        max_tokens=1024,
        output_mode="text",
        output_schema=None,
        seed,
        trace_id,
        call_id="",
        space_id="",
    ):
        self.current += 1
        self.peak = max(self.peak, self.current)
        try:
            await asyncio.sleep(self.delay_s)
            return CommandResult(ok=True, data={"text": "ok"}, latency_ms=1, token_usage=1)
        finally:
            self.current -= 1


@pytest.mark.asyncio
async def test_max_parallelism_caps_concurrency(settings, fake_redis, mem_store):
    provider = _ConcurrencyTrackingProvider()
    fan = 6
    nodes = [
        {
            "key": f"n{i}",
            "type": "llm",
            "config": {"model": "mock-large", "promptTemplate": "hi", "maxTokens": 8},
        }
        for i in range(fan)
    ]
    nodes.append(
        {
            "key": "slow",
            "type": "tool",
            "config": {
                "connectorId": "mock.echo",
                "operation": "sleep",
                "paramsMapping": {"sleepMs": "50"},
            },
        }
    )
    nodes.append({"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}})
    edges = [{"from": f"n{i}", "to": "end"} for i in range(fan)]
    edges.append({"from": "slow", "to": "end"})

    dsl = _dsl(nodes, edges, defaults={"timeoutMs": 5000, "maxParallelism": 2})
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store, llm_providers={"mock": provider})
    req = _req(dsl, initialVariables={})
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    assert events[-1].event_type == EventType.EXECUTION_COMPLETED
    assert provider.peak <= 2, f"并发峰值 {provider.peak} 超出 maxParallelism=2"
    assert provider.peak == 2, "并发度未用满，说明并行被意外串行化"


@pytest.mark.asyncio
async def test_execution_deadline_times_out(settings, fake_redis, mem_store):
    dsl = _dsl(
        [
            _sleep_node("a", 200),
            _sleep_node("b", 200),
            _sleep_node("c", 200),
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        [
            {"from": "a", "to": "b"},
            {"from": "b", "to": "c"},
            {"from": "c", "to": "end"},
        ],
    )
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _req(
        dsl, initialVariables={}, policy={"tokenBudget": 8000, "maxSteps": 30, "deadlineMs": 250}
    )
    await dispatcher.submit(req)
    await _await_task(dispatcher, req.execution_id)

    events = await mem_store.read_all(req.execution_id)
    assert events[-1].event_type == EventType.EXECUTION_TIMED_OUT, _types(events)
    assert all(e.node_key != "end" for e in events)
