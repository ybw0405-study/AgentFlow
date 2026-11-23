"""离线回归执行器：固定场景 → 确定性报告 → 基线比对。

复用单测的内存事件库与离线夹具（tests/unit/conftest、tests/support/mocks），
不另起 HTTP 栈。报告只保留确定字段：耗时、时间戳、事件 seq 一律不进基线。
语义断言（_verify）与基线比对是两道独立的闸：前者拦住「刷新基线把回归洗白」，
后者拦住断言没覆盖到的细粒度漂移（token 计量、输出文本、重试延迟等）。
"""

from __future__ import annotations

import asyncio
import json
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import fakeredis.aioredis

from app.config import Settings
from app.engine.dispatcher import Dispatcher
from app.engine.requests import ExecuteRequest
from app.errors import TransientNodeError
from app.events.log import EventLog
from app.events.types import EXECUTION_STATUS_OF_EVENT, TERMINAL_EVENTS, EventType
from app.governance.budget import BudgetLedger
from app.nodes import build_executors
from app.nodes.tool import ConnectorRegistry, ToolExecutor
from tests.support.mocks import FakeLLMProvider, FakeToolConnector
from tests.unit.conftest import InMemoryEventStore, NullPublisher

from .specs import SCENARIOS, Scenario

BASELINE_PATH = Path(__file__).with_name("baseline.json")


class EvalRegressionError(Exception):
    """回归语义被破坏（区别于基线漂移：这是引擎行为错误，刷新基线不能修复）。"""


def require(ok: bool, message: str) -> None:
    if not ok:
        raise EvalRegressionError(message)


class _ScriptedTransientProvider:
    """前 fail_times 次调用抛瞬时错误，其后委托 FakeLLMProvider。

    与 test_fault_injection 的 _FlakyOnceProvider 同构，只是失败次数可配：
    重试/退避路径需要「失败 N 次后成功」与「永远失败」两种确定性驱动。
    """

    name = "openai-compatible"

    def __init__(self, fail_times: int) -> None:
        self._fail_times = fail_times
        self._inner = FakeLLMProvider()
        self.calls = 0

    async def complete(self, **kwargs: Any) -> Any:
        self.calls += 1
        if self.calls <= self._fail_times:
            raise TransientNodeError("LLM-TRANSIENT", "eval scripted transient failure")
        return await self._inner.complete(**kwargs)


class _InterruptStore(InMemoryEventStore):
    """目标节点 NodeStarted 落库时发信号：此刻上游已完成且 checkpoint 已写。"""

    def __init__(self, node_key: str) -> None:
        super().__init__()
        self._node_key = node_key
        self.reached = asyncio.Event()

    async def append(self, execution_id, event_type, payload, *, node_key=None, node_io_spill=None):
        ev = await super().append(
            execution_id, event_type, payload, node_key=node_key, node_io_spill=node_io_spill
        )
        if str(event_type) == EventType.NODE_STARTED.value and node_key == self._node_key:
            self.reached.set()
        return ev


def _stack(settings, redis, store, provider) -> Dispatcher:
    event_log = EventLog(store, NullPublisher())
    budget = BudgetLedger(redis)
    executors = build_executors(redis, {"openai-compatible": provider})
    executors["tool"] = ToolExecutor(ConnectorRegistry(lambda cid: FakeToolConnector(redis, cid)))
    return Dispatcher(settings, store, event_log, redis, executors, budget)


def _request(scenario: Scenario) -> ExecuteRequest:
    # 固定 executionId/seed/traceId：callId 与派生 seed 都由它们决定，
    # 报告里的输出文本与 token 计量因此可逐次复现。
    eid = uuid.uuid5(uuid.NAMESPACE_URL, f"agentflow-eval:{scenario.name}")
    return ExecuteRequest.model_validate(
        {
            "executionId": str(eid),
            "definitionId": f"def-eval-{scenario.name}",
            "versionId": "ver-eval-1",
            "tenantId": "tenant-eval",
            "spaceId": "space-eval",
            "triggeredBy": "eval-harness",
            "idempotencyKey": eid.hex,
            "versionSnapshot": scenario.dsl,
            "authorizedConnectorIds": ["test.search", "test.echo", "test.flaky"],
            "initialVariables": scenario.initial_variables,
            "policy": {"tokenBudget": 8000, "maxSteps": 30, "deadlineMs": 60000},
            "traceId": uuid.uuid5(uuid.NAMESPACE_URL, f"agentflow-eval-trace:{scenario.name}").hex,
            "executionSeed": f"eval-seed:{scenario.name}",
        }
    )


async def _wait_terminal(store, execution_id: uuid.UUID, timeout_s: float = 15.0) -> str:
    for _ in range(int(timeout_s / 0.02)):
        events = await store.read_all(execution_id)
        terminal = [e for e in events if e.event_type in TERMINAL_EVENTS]
        if terminal:
            return str(EXECUTION_STATUS_OF_EVENT[terminal[-1].event_type])
        await asyncio.sleep(0.02)
    raise EvalRegressionError(f"execution {execution_id} 未在 {timeout_s}s 内收口")


def summarize(events: list, *, strict_order: bool) -> dict[str, Any]:
    """事件流 → 确定性报告。只取与调度语义相关的字段，剔除一切墙钟量。"""
    completed = [e for e in events if e.event_type == EventType.NODE_COMPLETED]
    failed = [e for e in events if e.event_type == EventType.NODE_FAILED]
    retries = [e for e in events if e.event_type == EventType.NODE_RETRY_SCHEDULED]
    terminal = next((e for e in reversed(events) if e.event_type in TERMINAL_EVENTS), None)

    attempts: dict[str, int] = {}
    statuses: dict[str, str] = {}
    branches: dict[str, str] = {}
    merged: dict[str, Any] = {}
    for e in events:
        if e.node_key is None:
            continue
        if e.event_type == EventType.NODE_COMPLETED:
            attempts[e.node_key] = e.payload.get("attempt", 1)
            statuses[e.node_key] = "SUCCEEDED"
            output = e.payload.get("output") or {}
            if "branch" in output:
                branches[e.node_key] = output["branch"]
            if "merged" in output:
                merged[e.node_key] = output["merged"]
        elif e.event_type == EventType.NODE_FAILED:
            attempts[e.node_key] = e.payload.get("attempt", 1)
            statuses[e.node_key] = "FAILED"
        elif e.event_type == EventType.NODE_SKIPPED:
            statuses[e.node_key] = "SKIPPED"

    report: dict[str, Any] = {
        "status": str(EXECUTION_STATUS_OF_EVENT[terminal.event_type]) if terminal else "OPEN",
        "completedKeys": sorted({e.node_key for e in completed}),
        "nodeStatuses": statuses,
        "attempts": attempts,
        "retryDelays": [e.payload["delayMs"] for e in retries],
        "failedCodes": [e.payload["errorCode"] for e in failed],
        "skipped": sorted({e.node_key for e in events if e.event_type == EventType.NODE_SKIPPED}),
        "branches": branches,
        "merged": merged,
        "result": terminal.payload.get("result") if terminal else None,
        "errorCode": terminal.payload.get("errorCode") if terminal else None,
        "tokenUsed": terminal.payload.get("tokenUsed", 0) if terminal else 0,
        "stepsUsed": terminal.payload.get("stepsUsed", 0) if terminal else 0,
    }
    if strict_order:
        report["completedOrder"] = [e.node_key for e in completed]
    return report


async def _run_plain(scenario: Scenario) -> dict[str, Any]:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    store = InMemoryEventStore()
    provider = (
        _ScriptedTransientProvider(scenario.fail_times)
        if scenario.fail_times
        else FakeLLMProvider()
    )
    settings = Settings(_env_file=None, checkpoint_interval=scenario.checkpoint_interval)
    dispatcher = _stack(settings, redis, store, provider)
    req = _request(scenario)
    await dispatcher.submit(req)
    await _wait_terminal(store, req.execution_id)
    events = await store.read_all(req.execution_id)
    report = summarize(events, strict_order=scenario.strict_order)
    if scenario.fail_times:
        report["providerCalls"] = provider.calls
    _verify(scenario.name, report)
    await redis.aclose()
    return report


async def _run_checkpoint_resume(scenario: Scenario) -> dict[str, Any]:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    store = _InterruptStore(scenario.interrupt_node or "")
    settings = Settings(_env_file=None, checkpoint_interval=scenario.checkpoint_interval)
    req = _request(scenario)

    dispatcher1 = _stack(settings, redis, store, FakeLLMProvider())
    await dispatcher1.submit(req)
    await asyncio.wait_for(store.reached.wait(), timeout=10)
    # 模拟进程崩溃：CancelledError 是 BaseException，调度器不会把它折叠成
    # ExecutionFailed 终态，事件流保持 OPEN，与真实崩溃现场一致。
    task = dispatcher1._tasks[req.execution_id]
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    await dispatcher1.shutdown()

    before = await store.read_all(req.execution_id)
    require(
        not any(e.event_type in TERMINAL_EVENTS for e in before),
        "checkpoint-resume: 崩溃现场不得落终态事件",
    )
    checkpoints = [e for e in before if e.event_type == EventType.CHECKPOINT_CREATED]
    require(checkpoints, "checkpoint-resume: 中断前必须已写 checkpoint")
    interrupt_nodes = checkpoints[-1].payload["completedNodes"]

    dispatcher2 = _stack(settings, redis, store, FakeLLMProvider())
    resumed = await dispatcher2.submit(req)
    require(resumed["resumed"] is True, "checkpoint-resume: 续跑必须走 resumed=True 路径")
    await _wait_terminal(store, req.execution_id)
    events = await store.read_all(req.execution_id)

    report = summarize(events, strict_order=True)
    counts = Counter(e.node_key for e in events if e.event_type == EventType.NODE_COMPLETED)
    report["resumed"] = True
    report["completionCounts"] = dict(sorted(counts.items()))
    report["interruptCheckpointNodes"] = interrupt_nodes
    report["sideEffectCount"] = int(await redis.get("af:fakecount:test.echo:sleep") or 0)
    _verify(scenario.name, report)
    await redis.aclose()
    return report


def _verify(name: str, report: dict[str, Any]) -> None:
    """场景级语义断言：基线可以被刷新，这些不变量不行。"""
    status = report["status"]
    if name == "linear-order":
        require(status == "SUCCEEDED", f"linear-order: 状态 {status}")
        require(
            report["completedOrder"] == ["start", "prepare", "draft", "enrich", "end"],
            f"linear-order: 拓扑序被破坏 {report['completedOrder']}",
        )
        require(report["result"]["prepped"] == "prepared", "linear-order: transform 变量丢失")
        require(
            report["result"]["query"] == "AgentFlow 如何保证恰好一次？",
            "linear-order: tool 参数映射漂移",
        )
    elif name == "retry-backoff":
        require(status == "SUCCEEDED", f"retry-backoff: 状态 {status}")
        require(report["attempts"].get("call") == 3, "retry-backoff: 应在第 3 次尝试成功")
        require(report["retryDelays"] == [100, 200], "retry-backoff: 指数退避延迟漂移")
        require(report["providerCalls"] == 3, "retry-backoff: provider 调用次数漂移")
    elif name == "retry-exhausted":
        require(status == "FAILED", f"retry-exhausted: 状态 {status}")
        require(report["errorCode"] == "LLM-TRANSIENT", "retry-exhausted: 错误码漂移")
        require(report["attempts"].get("call") == 2, "retry-exhausted: 必须受 maxAttempts 约束")
        require(report["retryDelays"] == [100], "retry-exhausted: 退避延迟漂移")
        require(report["providerCalls"] == 2, "retry-exhausted: 重试耗尽后不得继续调用")
    elif name == "tool-no-retry":
        require(status == "FAILED", f"tool-no-retry: 状态 {status}")
        require(report["errorCode"] == "TOOL-FLAKY", "tool-no-retry: 错误码漂移")
        require(report["attempts"].get("flaky") == 1, "tool-no-retry: idempotent=false 禁止重试")
        require(report["retryDelays"] == [], "tool-no-retry: 不得调度重试")
    elif name in ("condition-high", "condition-low"):
        high = name == "condition-high"
        taken, skipped = ("yes", "no") if high else ("no", "yes")
        branch = "high" if high else "low"
        grade = "PASS" if high else "FAIL"
        # skipped_tail 挂在 no 分支下：high 命中时级联 SKIPPED，low 命中时照常执行
        tail_status = "SKIPPED" if high else "SUCCEEDED"
        require(status == "SUCCEEDED", f"{name}: 状态 {status}")
        require(report["branches"].get("gate") == branch, f"{name}: 分支选择漂移")
        require(report["nodeStatuses"].get(taken) == "SUCCEEDED", f"{name}: 命中分支未执行")
        require(report["nodeStatuses"].get(skipped) == "SKIPPED", f"{name}: 未命中分支未跳过")
        require(
            report["nodeStatuses"].get("skipped_tail") == tail_status,
            f"{name}: skipped_tail 状态应为 {tail_status}",
        )
        require(report["result"]["grade"] == grade, f"{name}: 收口结果漂移")
    elif name == "parallel-join":
        require(status == "SUCCEEDED", f"parallel-join: 状态 {status}")
        require(
            report["merged"].get("par") == {"left": {"side": "left"}, "right": {"side": "right"}},
            "parallel-join: join=all 必须汇合全部分支",
        )
        require(
            report["result"] == {"left": "left", "right": "right"},
            "parallel-join: 汇合输出映射漂移",
        )
    elif name == "checkpoint-resume":
        require(status == "SUCCEEDED", f"checkpoint-resume: 状态 {status}")
        require(report["resumed"] is True, "checkpoint-resume: resumed 标记丢失")
        require(
            all(count == 1 for count in report["completionCounts"].values()),
            f"checkpoint-resume: 已完成节点被重复执行 {report['completionCounts']}",
        )
        require(
            report["completedOrder"] == ["start", "prepare", "nap", "finalize", "end"],
            "checkpoint-resume: 续跑后拓扑序被破坏",
        )
        require(report["sideEffectCount"] == 1, "checkpoint-resume: 工具副作用必须恰好一次")
        # checkpoint_payload 的 completedNodes 按 key 字典序排序，不是执行序
        require(
            report["interruptCheckpointNodes"] == ["prepare", "start"],
            "checkpoint-resume: 中断点 checkpoint 快照漂移",
        )
        require(
            report["result"]["prepped"] == "prepared",
            "checkpoint-resume: 续跑必须复原 checkpoint 里的变量",
        )
    else:
        raise EvalRegressionError(f"未知场景 {name}：缺少语义断言")


async def run_all() -> dict[str, Any]:
    scenarios: dict[str, Any] = {}
    for scenario in SCENARIOS:
        if scenario.interrupt_node:
            scenarios[scenario.name] = await _run_checkpoint_resume(scenario)
        else:
            scenarios[scenario.name] = await _run_plain(scenario)
    return {"version": 1, "scenarios": scenarios}


def load_baseline() -> dict[str, Any]:
    return json.loads(BASELINE_PATH.read_text(encoding="utf-8"))


def write_baseline(report: dict[str, Any]) -> None:
    BASELINE_PATH.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def diff_scenarios(actual: dict[str, Any], baseline: dict[str, Any]) -> list[str]:
    a = actual.get("scenarios", {})
    b = baseline.get("scenarios", {})
    return [name for name in sorted(set(a) | set(b)) if a.get(name) != b.get(name)]
