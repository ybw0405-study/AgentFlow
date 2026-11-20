"""端到端调度（内存 store + fakeredis）：拓扑序、事件序列、token 统计、
预算硬停止、取消、并发幂等、崩溃恢复、恰好一次。"""

from __future__ import annotations

import asyncio

from app.commands.base import CommandResult
from app.engine.requests import ExecuteRequest
from app.events.types import TERMINAL_EVENTS

from .conftest import make_execute_request, make_stack


async def wait_terminal(store, execution_id, timeout_s: float = 10.0) -> str:
    from app.events.types import EXECUTION_STATUS_OF_EVENT

    for _ in range(int(timeout_s / 0.05)):
        events = await store.read_all(execution_id)
        # 终态事件之后可能跟随 CheckpointCreated，因此查「是否出现过终态」而非末尾
        terminal = [e for e in events if e.event_type in TERMINAL_EVENTS]
        if terminal:
            return str(EXECUTION_STATUS_OF_EVENT[terminal[-1].event_type])
        await asyncio.sleep(0.05)
    raise AssertionError(f"execution {execution_id} did not reach terminal in time")


async def test_happy_path_topological_order_and_tokens(settings, fake_redis, mem_store, demo_dsl):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    result = await dispatcher.submit(req)
    assert result["status"] == "PENDING"
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    types = [str(e.event_type) for e in events]
    assert types[0] == "ExecutionCreated"
    assert types[1] == "ExecutionStarted"
    assert types[-1] == "ExecutionCompleted"

    completed = [e for e in events if str(e.event_type) == "NodeCompleted"]
    order = [e.node_key for e in completed]
    assert order[0] == "start"
    assert order[-1] == "end"
    assert order.index("draft") < order.index("final")
    assert order.index("enrich") < order.index("final")

    # 每个 llm/tool 命令成对落事件（§8.2.3）
    issued = [e for e in events if str(e.event_type) == "CommandIssued"]
    done = [e for e in events if str(e.event_type) == "CommandCompleted"]
    assert len(issued) == len(done) == 3
    assert {e.payload["callId"] for e in issued} == {e.payload["callId"] for e in done}

    # token 统计
    final = events[-1].payload
    assert final["tokenUsed"] > 0 and final["stepsUsed"] == 5
    assert final["result"]["answer"]

    # 节点 I/O 可查
    io = await mem_store.get_node_io(req.execution_id, "draft")
    assert io["in"]["payload"]["prompt"].startswith("请根据问题起草答案要点")
    assert io["out"] is None or io["out"]["payload"]  # OUT 经事件溢出才写；IN 必有


async def test_budget_hard_stop(settings, fake_redis, mem_store, demo_dsl):
    dispatcher, _, budget = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(
            demo_dsl, policy={"tokenBudget": 1, "maxSteps": 30, "deadlineMs": 60000}
        )
    )
    await dispatcher.submit(req)
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "FAILED"
    events = await mem_store.read_all(req.execution_id)
    last = events[-1].payload
    assert last["errorCode"] == "EXEC-BUDGET_EXHAUSTED"
    assert last["budgetStatus"] == "BUDGET_EXHAUSTED"
    assert await budget.used(req.execution_id) <= 1  # tokenUsed ≤ budget 恒成立


async def test_concurrent_submit_same_execution_is_idempotent(
    settings, fake_redis, mem_store, demo_dsl
):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    results = await asyncio.gather(*[dispatcher.submit(req) for _ in range(10)])
    assert all(r["executionId"] == str(req.execution_id) for r in results)
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "SUCCEEDED"
    events = await mem_store.read_all(req.execution_id)
    created = [e for e in events if str(e.event_type) == "ExecutionCreated"]
    assert len(created) == 1  # 只有一次 ExecutionCreated


async def test_crash_recovery_completes_and_side_effects_exactly_once(
    settings, fake_redis, mem_store, demo_dsl
):
    """模拟崩溃：写入 ExecutionCreated + 部分事件后换新 dispatcher 恢复。"""
    # dispatcher1 模拟“崩溃的进程”：put_spec + ExecutionCreated 后不再工作
    req_dict = make_execute_request(demo_dsl)
    req = ExecuteRequest.model_validate(req_dict)
    eid = req.execution_id

    # “崩溃现场”：spec 已落、ExecutionCreated 已落，但没有任何节点事件
    await mem_store.put_spec(eid, req.canonical_dump())
    await mem_store.append(
        eid, "ExecutionCreated", {"executionSeed": "deadbeef", "traceId": req.trace_id}
    )
    # dispatcher1 直接废弃（不跑 _run）

    dispatcher2, _, _ = make_stack(settings, fake_redis, mem_store)
    await dispatcher2._recover_one(eid, attempts=1)
    status = await wait_terminal(mem_store, eid)
    assert status == "SUCCEEDED"

    # 恰好一次：检索副作用计数 == 1
    count = await fake_redis.get("af:fakecount:test.search:search")
    assert int(count) == 1


async def test_cancel_running_execution(settings, fake_redis, mem_store, demo_dsl):
    class SlowLLM:
        name = "openai-compatible"

        async def complete(self, **kwargs):
            await asyncio.sleep(0.3)
            return CommandResult(
                ok=True, data={"text": "slow", "parsed": None}, token_usage=2, latency_ms=300
            )

    dispatcher, _, _ = make_stack(
        settings, fake_redis, mem_store, llm_providers={"openai-compatible": SlowLLM()}
    )
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    await dispatcher.submit(req)
    await asyncio.sleep(0.05)
    cancel_result = await dispatcher.cancel(req.execution_id)
    assert cancel_result["ok"] is True
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "CANCELLED"


async def test_unauthorized_connector_fails_permanently(settings, fake_redis, mem_store, demo_dsl):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(demo_dsl, authorizedConnectorIds=[])
    )  # 未授权 test.search
    await dispatcher.submit(req)
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "FAILED"
    events = await mem_store.read_all(req.execution_id)
    failed = next(e for e in events if str(e.event_type) == "NodeFailed" and e.node_key == "enrich")
    assert failed.payload["errorCode"] == "CONN-FORBIDDEN-001"
    assert failed.payload["class"] == "PERMANENT"


async def test_missing_required_variable_is_permanent(settings, fake_redis, mem_store, demo_dsl):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(demo_dsl, initialVariables={})
    )  # 缺 required question
    await dispatcher.submit(req)
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "FAILED"
    events = await mem_store.read_all(req.execution_id)
    failed = next(e for e in events if str(e.event_type) == "NodeFailed" and e.node_key == "start")
    assert failed.payload["errorCode"] == "EXEC-INPUT-001"


async def test_replay_state_fold(settings, fake_redis, mem_store, demo_dsl):
    from app.engine.replay import rebuild_state

    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    await dispatcher.submit(req)
    await wait_terminal(mem_store, req.execution_id)
    events = await mem_store.read_all(req.execution_id)
    state = rebuild_state(req.execution_id, events)
    assert state.status == "SUCCEEDED"
    assert state.result["answer"]
    assert state.nodes["draft"].status == "SUCCEEDED"
    assert state.nodes["final"].output["text"]
    assert state.token_used > 0


async def test_checkpoint_written_periodically(settings, fake_redis, mem_store, demo_dsl):
    settings.checkpoint_interval = 3
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    await dispatcher.submit(req)
    await wait_terminal(mem_store, req.execution_id)
    events = await mem_store.read_all(req.execution_id)
    checkpoints = [e for e in events if str(e.event_type) == "CheckpointCreated"]
    assert checkpoints, "expected at least one checkpoint"
    assert "completedNodes" in checkpoints[-1].payload
