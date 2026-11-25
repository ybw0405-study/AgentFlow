"""P1 故障注入：租约丢失 / 事件版本冲突 / 节点超时 / 重试 / 并行上限 / deadline。

对应提示词 §8 的错误处理与约束验收；全部在内存事件库 + fakeredis 上跑，无外部依赖。
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import UTC, datetime

import pytest
from redis.exceptions import ConnectionError as RedisConnectionError

from app.commands.base import CommandResult
from app.dsl.models import WorkflowDsl
from app.engine.requests import ExecuteRequest
from app.engine.scheduler import LeaseLostError
from app.engine.state import ExecutionState
from app.errors import EventConcurrencyError, EventTerminalError, TransientNodeError
from app.events.store import EventStore
from app.events.types import TERMINAL_EVENTS, EventType
from app.nodes.base import Policy
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
            "connectorId": "test.echo",
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

    name = "openai-compatible"

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
    dispatcher, _, _ = make_stack(
        settings, fake_redis, mem_store, llm_providers={"openai-compatible": provider}
    )
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
    name = "openai-compatible"

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
    dispatcher, _, _ = make_stack(
        settings, fake_redis, mem_store, llm_providers={"openai-compatible": provider}
    )
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

    name = "openai-compatible"

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
                "connectorId": "test.echo",
                "operation": "sleep",
                "paramsMapping": {"sleepMs": "50"},
            },
        }
    )
    nodes.append({"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}})
    edges = [{"from": f"n{i}", "to": "end"} for i in range(fan)]
    edges.append({"from": "slow", "to": "end"})

    dsl = _dsl(nodes, edges, defaults={"timeoutMs": 5000, "maxParallelism": 2})
    dispatcher, _, _ = make_stack(
        settings, fake_redis, mem_store, llm_providers={"openai-compatible": provider}
    )
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


# ---- Redis 故障路径----


class _ExplodingLease:
    """renew / held_by_me 抛 Redis 连接异常，模拟 Redis 抖动。"""

    key = "af:lease:exploding"

    async def renew(self) -> bool:
        raise RedisConnectionError("redis down")

    async def held_by_me(self) -> bool:
        raise RedisConnectionError("redis down")

    async def release(self) -> None:
        # Redis 全挂时 release 同样会抛（而不是静默 return None）：关停测试据此
        # 验证「清理失败也不能让 _run 卡在 finally / _tasks 残留」。
        raise RedisConnectionError("redis down")


@pytest.mark.asyncio
async def test_renew_loop_connection_error_marks_lease_lost(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    settings.lease_renew_ms = 1
    state = ExecutionState(execution_id=uuid.uuid4(), seed="s")
    await dispatcher._renew_loop(_ExplodingLease(), asyncio.Event(), state)
    assert state.lease_lost is True


@pytest.mark.asyncio
async def test_stop_conditions_redis_error_is_lease_lost(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    state = ExecutionState(execution_id=uuid.uuid4(), seed="s")
    with pytest.raises(LeaseLostError):
        await dispatcher._scheduler._check_stop_conditions(
            state.execution_id, state, None, _ExplodingLease()
        )
    assert state.lease_lost is True


@pytest.mark.asyncio
async def test_redis_unavailable_run_stops_silently(settings, fake_redis, mem_store):
    """Redis 不可达时按 LeaseLost 静默停止，绝不落 ExecutionFailed 终态。"""
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
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
        variables={"ok": {"type": "string", "required": False}},
    )
    eid = uuid.uuid4()
    state = ExecutionState(execution_id=eid, seed="s")
    status = await dispatcher._scheduler.run(
        execution_id=eid,
        dsl=WorkflowDsl.model_validate(dsl),
        state=state,
        trace_id="trace",
        policy=Policy(),
        authorized_connector_ids=frozenset(),
        lease=_ExplodingLease(),
    )
    assert status == "LEASE_LOST"
    events = await mem_store.read_all(eid)
    assert not any(e.event_type in TERMINAL_EVENTS for e in events)


class _SetFailsRedis:
    async def set(self, *args, **kwargs):
        raise RedisConnectionError("redis down")


@pytest.mark.asyncio
async def test_cancel_redis_failure_returns_dependency_error(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    dispatcher._redis = _SetFailsRedis()
    result = await dispatcher.cancel(uuid.uuid4())
    assert result["ok"] is False
    assert result["status"] == "DEPENDENCY_UNAVAILABLE"
    assert result["errorCode"]


@pytest.mark.asyncio
async def test_shutdown_cancels_and_awaits_sweepers(settings, fake_redis, mem_store):
    """关停必须 drain 真实在跑的 _tasks，之后才允许关闭 store/redis。

    以前的测试全程 _tasks 为空，恰好绕过了「只停扫描任务、不停执行任务」的缺陷。
    这里注入一个受控的在途任务，并让它的清理阶段踩到「Redis 全挂时 release 也抛」
    ——清理失败也不能让任务卡在 finally / 让 _tasks 残留。
    """
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    dispatcher.start_sweep(interval_s=60)
    dispatcher.start_gate_sweeper(interval_s=60)
    sweep, gate = dispatcher._sweep_task, dispatcher._gate_task

    eid = uuid.uuid4()
    order: list[str] = []
    gate_event = asyncio.Event()

    async def in_flight():
        try:
            await gate_event.wait()  # 只有被 cancel 才结束
        finally:
            # 模拟 _run 的清理：release 抛异常也必须让任务收口并移除 _tasks
            try:
                await _ExplodingLease().release()
            except RedisConnectionError:
                order.append("cleanup-failed")
            dispatcher._tasks.pop(eid, None)
            order.append("task-finished")

    task = asyncio.create_task(in_flight())
    dispatcher._tasks[eid] = task
    await asyncio.sleep(0)  # 让在途任务真正进入 await

    # 三级收口：停止接收 → drain 在途 → 停后台。返回时在途任务必须已结束。
    await dispatcher.shutdown()

    # main 的收尾顺序：只有 shutdown 返回（在途 drain 完）之后才允许关 store/redis。
    order.append("store-closed")
    order.append("redis-closed")

    assert sweep.done() and gate.done()
    assert dispatcher._sweep_task is None and dispatcher._gate_task is None
    assert task.done(), "在途执行任务必须已被 cancel 并收口"
    assert dispatcher._tasks == {}, "drain 后不得残留幽灵 _tasks 条目"
    assert "cleanup-failed" in order  # release 真的抛了，且没有拖垮收口
    assert "task-finished" in order
    assert order.index("task-finished") < order.index("store-closed") < order.index("redis-closed")


@pytest.mark.asyncio
async def test_shutdown_stops_accepting_new_submissions(settings, fake_redis, mem_store):
    """B4 第一级：关停后 submit 不得再建任务、不得再落事件。"""
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    await dispatcher.shutdown()

    req = _req(
        _dsl(
            [{"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}}],
            [],
        )
    )
    result = await dispatcher.submit(req)

    assert result["status"] == "SHUTTING_DOWN"
    assert "note" in result
    assert req.execution_id not in dispatcher._tasks
    assert await mem_store.read_all(req.execution_id) == []


# ---- 终态不可逆----


class _ScriptedConn:
    """驱动真实 EventStore.append 的最小 asyncpg 替身：只模拟 tracker 状态与 seq。

    upsert 的 SQL 语义（CASE 终态优先）由 test_event_store_pg.py 在真实 PG 上验证；
    这里用替身验证「终态后拒绝非终态 append」这条不依赖 docker 的应用层不变量。
    """

    def __init__(self, tracker_status=None):
        self.tracker_status = tracker_status
        self.seq = 0
        self.appended = []
        self.tracker_sql = []

    def transaction(self):
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, sql, *args):
        if "INSERT INTO execution_tracker" in sql:
            self.tracker_sql.append(sql)
            incoming = args[1]
            # 模拟 CASE WHEN ... THEN 'TERMINAL'：已是终态时不被回写
            if not (self.tracker_status == "TERMINAL" and "CASE WHEN" in sql):
                self.tracker_status = incoming

    async def fetchrow(self, sql, *args):
        if "SELECT status FROM execution_tracker" in sql:
            return {"status": self.tracker_status} if self.tracker_status else None
        if "COALESCE(MAX(aggregate_version)" in sql:
            return {"v": len(self.appended) + 1}
        if "INSERT INTO execution_event" in sql:
            self.seq += 1
            self.appended.append(args)
            return {"seq": self.seq, "created_at": datetime.now(UTC)}
        return None


class _ScriptedPool:
    def __init__(self, conn):
        self._conn = conn

    def acquire(self):
        return self._conn


@pytest.mark.asyncio
async def test_terminal_tracker_is_irreversible():
    conn = _ScriptedConn()
    store = EventStore("unused")
    store._pool = _ScriptedPool(conn)

    eid = uuid.uuid4()
    await store.append(eid, "ExecutionCreated", {"traceId": "t"})
    assert conn.tracker_status == "OPEN"

    await store.append(eid, "ExecutionCompleted", {"result": {}})
    assert conn.tracker_status == "TERMINAL"
    assert "CASE WHEN" in conn.tracker_sql[-1]  # upsert 走终态优先

    # 终态后迟到的非终态事件必须被拒绝：tracker 不回写、事件不落库
    with pytest.raises(EventTerminalError):
        await store.append(eid, "NodeStarted", {"i": 9}, node_key="n1")
    assert conn.tracker_status == "TERMINAL"
    assert len(conn.appended) == 2


# ---- liveness / readiness 探针----


class _ProbeStore:
    def __init__(self, up: bool) -> None:
        self.up = up

    async def ping(self) -> None:
        if not self.up:
            raise RuntimeError("pg down")


class _ProbeRedis:
    def __init__(self, up: bool) -> None:
        self.up = up

    async def ping(self) -> bool:
        if not self.up:
            raise RuntimeError("redis down")
        return True


def _probe_app(settings, *, pg_up: bool = True, redis_up: bool = True):
    from app.main import create_app

    app = create_app(settings)
    # ASGITransport 不跑 lifespan，手动装配探针依赖与 settings
    app.state.settings = settings
    app.state.store = _ProbeStore(pg_up)
    app.state.redis = _ProbeRedis(redis_up)
    return app


@pytest.mark.asyncio
async def test_health_live_is_always_ok_even_when_dependencies_down(settings):
    from httpx import ASGITransport, AsyncClient

    app = _probe_app(settings, pg_up=False, redis_up=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        assert (await client.get("/internal/health/live")).status_code == 200
        assert (await client.get("/internal/health")).status_code == 200  # 兼容别名


@pytest.mark.asyncio
async def test_health_ready_returns_503_on_dependency_down(settings):
    from httpx import ASGITransport, AsyncClient

    app = _probe_app(settings, pg_up=True, redis_up=False)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.get("/internal/health/ready")
    assert resp.status_code == 503
    assert resp.json()["status"] == "DEGRADED"
    assert resp.json()["checks"] == {"postgres": "UP", "redis": "DOWN"}


@pytest.mark.asyncio
async def test_health_ready_ok_and_probes_need_no_token(settings):
    from httpx import ASGITransport, AsyncClient

    app = _probe_app(settings, pg_up=True, redis_up=True)
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        # 不带 X-Internal-Token：探针免鉴权（不是 401）
        resp = await client.get("/internal/health/ready")
        assert resp.status_code == 200
        assert resp.json()["checks"] == {"postgres": "UP", "redis": "UP"}
        # 非探针的 /internal/** 仍强制鉴权
        assert (await client.get("/internal/events")).status_code == 401


# ---- 派发状态一致性----


class _AcceptingDispatcher:
    async def submit(self, body):
        return {"executionId": str(body.execution_id), "status": "PENDING", "resumed": False}


class _RejectingDispatcher:
    async def submit(self, body):
        return {
            "executionId": str(body.execution_id),
            "status": "RUNNING",
            "resumed": False,
            "note": "lease held by another worker",
        }


def _dispatch_app(settings, dispatcher):
    from app.main import create_app

    app = create_app(settings)
    app.state.settings = settings
    app.state.dispatcher = dispatcher
    return app


@pytest.mark.asyncio
async def test_execute_returns_202_when_dispatch_accepted(settings):
    from httpx import ASGITransport, AsyncClient

    app = _dispatch_app(settings, _AcceptingDispatcher())
    body = make_execute_request({})
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/internal/executions",
            json=body,
            headers={"X-Internal-Token": settings.internal_token},
        )
    assert resp.status_code == 202  # 正常派发仍是 202，契约不变
    assert resp.json()["status"] == "PENDING"


@pytest.mark.asyncio
async def test_execute_returns_409_when_engine_did_not_dispatch(settings):
    from httpx import ASGITransport, AsyncClient

    app = _dispatch_app(settings, _RejectingDispatcher())
    body = make_execute_request({})
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        resp = await client.post(
            "/internal/executions",
            json=body,
            headers={"X-Internal-Token": settings.internal_token},
        )
    assert resp.status_code == 409  # 「接受但未派发」绝不能回 202
    assert resp.json()["code"] == "EXEC-DISPATCH_REJECTED-001"
