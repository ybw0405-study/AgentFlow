"""llm 节点：真实模型名、推理型 maxTokens 下限与下发留痕。

画布从 mock 切到真实 provider 后，DSL 里沿用的 mock-* 占位名会被 provider 回落到
服务端默认模型；此时若仍把占位名写进 output 与事件，事后审计就看不出实际用了哪个
模型。这里锁住「声明值 / 实际值」的区分，以及推理型模型的输出预算下限。
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest

from app import errors as app_errors
from app.commands.base import CommandResult
from app.commands.providers.model_capabilities import REASONING_MIN_OUTPUT_TOKENS
from app.dsl.models import LlmConfig, LlmNode
from app.errors import PermanentNodeError
from app.events.types import EventType
from app.nodes.base import NodeContext, Policy
from app.nodes.llm import LlmExecutor


class _FixedProvider:
    """返回固定模型名，模拟 openai-compatible 的 _resolve_model 行为。"""

    name = "openai-compatible"

    def __init__(self, resolved: str | None) -> None:
        self._resolved = resolved

    async def complete(self, **_: Any) -> CommandResult:
        extra: dict[str, Any] = {
            "usage": {"prompt": 1, "completion": 2, "total": 3},
        }
        if self._resolved is not None:
            extra["model"] = self._resolved
        return CommandResult(
            ok=True, data={"text": "ok", "parsed": None}, token_usage=3, latency_ms=5, extra=extra
        )


class _CapturingProvider(_FixedProvider):
    def __init__(self, resolved: str | None = None) -> None:
        super().__init__(resolved)
        self.max_tokens: int | None = None

    async def complete(self, **kwargs: Any) -> CommandResult:
        self.max_tokens = kwargs.get("max_tokens")
        return await super().complete(**kwargs)


class _RecordingBudget:
    def __init__(self) -> None:
        self.requested: int | None = None
        self.settled: list[tuple[int, int]] = []

    async def reserve(self, _execution_id: Any, _budget: int, requested: int) -> int:
        self.requested = requested
        return requested

    async def settle(self, _execution_id: Any, grant: int, actual: int) -> None:
        self.settled.append((grant, actual))


def _node(model: str) -> LlmNode:
    return LlmNode(
        key="draft",
        type="llm",
        config=LlmConfig(
            provider="openai-compatible", model=model, prompt_template="{{ vars.question }}"
        ),
    )


def _ctx(emitted: list[tuple[str, dict[str, Any]]]) -> NodeContext:
    async def emit(event_type: Any, payload: dict[str, Any], *, node_key: str | None = None):
        emitted.append((str(event_type), payload))
        return None  # 返回值不被节点使用

    return NodeContext(
        execution_id=uuid.uuid4(),
        node_key="draft",
        attempt=0,
        seed="seed",
        trace_id="trace",
        variables={"question": "什么是事件溯源"},
        variables_spec={},
        outputs={},
        node_statuses={},
        policy=Policy(),
        budget=None,
        emit=emit,
    )


async def test_records_resolved_model_when_provider_substitutes() -> None:
    emitted: list[tuple[str, dict[str, Any]]] = []
    executor = LlmExecutor({"openai-compatible": _FixedProvider("deepseek-chat")})

    result = await executor.execute(_node("mock-large"), _ctx(emitted))

    assert result.output["model"] == "deepseek-chat"
    assert result.output["requestedModel"] == "mock-large"
    completed = [p for t, p in emitted if t == "CommandCompleted"]
    assert completed and completed[0]["model"] == "deepseek-chat"
    assert completed[0]["requestedModel"] == "mock-large"


async def test_falls_back_to_declared_model_when_provider_reports_none() -> None:
    emitted: list[tuple[str, dict[str, Any]]] = []
    executor = LlmExecutor({"openai-compatible": _FixedProvider(None)})

    result = await executor.execute(_node("mock-large"), _ctx(emitted))

    assert result.output["model"] == "mock-large"
    assert "requestedModel" not in result.output
    completed = [p for t, p in emitted if t == "CommandCompleted"]
    assert completed and completed[0]["model"] == "mock-large"


async def test_no_requested_model_when_names_match() -> None:
    emitted: list[tuple[str, dict[str, Any]]] = []
    executor = LlmExecutor({"openai-compatible": _FixedProvider("deepseek-chat")})

    result = await executor.execute(_node("deepseek-chat"), _ctx(emitted))

    assert result.output["model"] == "deepseek-chat"
    assert "requestedModel" not in result.output


async def test_reasoning_model_raises_output_budget() -> None:
    """1024 会被思维链吃光：下发前抬到下限，且事件与快照都记下这次抬升。"""
    emitted: list[tuple[str, dict[str, Any]]] = []
    provider = _CapturingProvider("deepseek-flash")
    executor = LlmExecutor({"openai-compatible": provider})

    result = await executor.execute(_node("deepseek-flash"), _ctx(emitted))

    assert provider.max_tokens == REASONING_MIN_OUTPUT_TOKENS
    assert result.input_snapshot is not None
    assert result.input_snapshot["maxTokens"] == REASONING_MIN_OUTPUT_TOKENS
    assert result.input_snapshot["configuredMaxTokens"] == 1024
    assert result.input_snapshot["reasoningAdjusted"] is True
    issued = [p for t, p in emitted if t == str(EventType.COMMAND_ISSUED)]
    assert issued and issued[0]["reasoningAdjusted"] is True
    assert issued[0]["configuredMaxTokens"] == 1024


async def test_chat_model_keeps_configured_max_tokens() -> None:
    emitted: list[tuple[str, dict[str, Any]]] = []
    provider = _CapturingProvider("deepseek-chat")
    executor = LlmExecutor({"openai-compatible": provider})

    result = await executor.execute(_node("deepseek-chat"), _ctx(emitted))

    assert provider.max_tokens == 1024
    assert result.input_snapshot is not None
    assert "reasoningAdjusted" not in result.input_snapshot
    issued = [p for t, p in emitted if t == str(EventType.COMMAND_ISSUED)]
    assert issued and "reasoningAdjusted" not in issued[0]


async def test_explicit_large_max_tokens_is_not_lowered() -> None:
    node = _node("deepseek-v4-pro")
    node.config.max_tokens = 8192
    provider = _CapturingProvider("deepseek-v4-pro")
    executor = LlmExecutor({"openai-compatible": provider})

    await executor.execute(node, _ctx([]))

    assert provider.max_tokens == 8192


async def test_connection_id_requires_authorization_like_tool_path() -> None:
    """SEC-4：LLM 走 connectionId 时也必须校验授权集合，与 tool 节点同口径。"""
    node = LlmNode(
        key="draft",
        type="llm",
        config=LlmConfig(
            connection_id="conn-not-authorized",
            model="deepseek-chat",
            prompt_template="{{ vars.question }}",
        ),
    )
    # connector_llms 传入但授权集合为空 → 必须在取 provider 前就被拒
    executor = LlmExecutor({}, connector_llms=object())  # type: ignore[arg-type]

    with pytest.raises(PermanentNodeError) as exc:
        await executor.execute(node, _ctx([]))

    assert exc.value.code == "CONN-FORBIDDEN-001"


async def test_reasoning_raise_is_included_in_budget_reservation() -> None:
    """预占请求量必须同步放大，否则 grant 会把抬升后的 maxTokens 又钳回去。"""
    emitted: list[tuple[str, dict[str, Any]]] = []
    provider = _CapturingProvider("deepseek-flash")
    budget = _RecordingBudget()
    ctx = _ctx(emitted)
    ctx.policy = Policy(token_budget=10_000)
    ctx.budget = budget  # type: ignore[assignment]
    executor = LlmExecutor({"openai-compatible": provider})

    await executor.execute(_node("deepseek-flash"), ctx)

    assert budget.requested is not None
    assert budget.requested >= REASONING_MIN_OUTPUT_TOKENS
    assert provider.max_tokens == REASONING_MIN_OUTPUT_TOKENS


class _RaisingProvider:
    """complete() 抛异常，覆盖 provider 未返回 CommandResult 的失败路径。"""

    name = "openai-compatible"

    def __init__(self, error: BaseException) -> None:
        self._error = error

    async def complete(self, **_: Any) -> CommandResult:
        raise self._error


@pytest.mark.parametrize(
    ("error", "expected_prefix"),
    [
        (app_errors.TransientNodeError("LLM-UPSTREAM-001", "boom"), "LLM-UPSTREAM-001"),
        (RuntimeError("socket reset"), "LLM-UPSTREAM-001"),
    ],
)
async def test_settles_grant_when_provider_raises(
    error: BaseException, expected_prefix: str
) -> None:
    """provider 抛异常时也必须回冲 grant，否则 pending>0 让 reconcile 永久跳过。"""
    budget = _RecordingBudget()
    ctx = _ctx([])
    ctx.policy = Policy(token_budget=10_000)
    ctx.budget = budget  # type: ignore[assignment]
    executor = LlmExecutor({"openai-compatible": _RaisingProvider(error)})

    with pytest.raises((app_errors.TransientNodeError, RuntimeError)) as exc:
        await executor.execute(_node("deepseek-chat"), ctx)

    assert expected_prefix in str(exc.value)
    assert budget.requested is not None
    # 关键断言：异常路径用 0 结算，且只结算一次
    assert budget.settled == [(budget.requested, 0)]


class _StreamingProvider(_FixedProvider):
    """返回带 deltas 的结果，模拟 AF_LLM_STREAM=true 的 openai-compatible。"""

    def __init__(self, deltas: list[str]) -> None:
        super().__init__("deepseek-chat")
        self._deltas = deltas

    async def complete(self, **kwargs: Any) -> CommandResult:
        result = await super().complete(**kwargs)
        result.extra["deltas"] = self._deltas
        return result


async def test_stream_deltas_emitted_as_output_delta_before_completed() -> None:
    """流式增量必须以 OutputDelta 事件逐个推送，且严格排在 CommandCompleted 之前。"""
    emitted: list[tuple[str, dict[str, Any]]] = []
    executor = LlmExecutor({"openai-compatible": _StreamingProvider(["事件", "溯源"])})

    result = await executor.execute(_node("deepseek-chat"), _ctx(emitted))

    # 完整结果不变，仍走 CommandCompleted 的既有语义
    assert result.output["text"] == "ok"

    event_names = [t for t, _ in emitted]
    deltas = [p for t, p in emitted if t == str(EventType.OUTPUT_DELTA)]
    completed_idx = event_names.index(str(EventType.COMMAND_COMPLETED))

    # 增量按序逐个推送，callId 与 CommandCompleted 一致（由 make_call_id 确定性派生）
    assert len(deltas) == 2
    assert [d["text"] for d in deltas] == ["事件", "溯源"]
    assert [d["index"] for d in deltas] == [0, 1]
    assert deltas[0]["callId"] == deltas[1]["callId"]
    completed = next(p for t, p in emitted if t == str(EventType.COMMAND_COMPLETED))
    assert deltas[0]["callId"] == completed["callId"]
    # OutputDelta 必须排在 CommandCompleted 之前
    assert event_names.index(str(EventType.OUTPUT_DELTA)) < completed_idx


async def test_no_deltas_emits_no_output_delta_events() -> None:
    """非流式 / 无增量时，事件序列里不应出现 OutputDelta，行为与旧版逐字节一致。"""
    emitted: list[tuple[str, dict[str, Any]]] = []
    executor = LlmExecutor({"openai-compatible": _FixedProvider("deepseek-chat")})

    await executor.execute(_node("deepseek-chat"), _ctx(emitted))

    event_names = [t for t, _ in emitted]
    assert str(EventType.OUTPUT_DELTA) not in event_names
    assert str(EventType.COMMAND_COMPLETED) in event_names


async def test_cancelled_provider_settles_reserved_budget() -> None:
    """P1-2 回归：provider 被取消（CancelledError）时预占必须回冲。

    CancelledError 继承 BaseException，不被 except Exception 捕获；若不显式处理，
    grant 泄漏、:inflight +1，同执行后续节点会被虚减余额提前触发预算硬停止。
    """
    import asyncio

    class _CancellingProvider:
        name = "openai-compatible"

        async def complete(self, **_: Any) -> CommandResult:
            raise asyncio.CancelledError()

    emitted: list[tuple[str, dict[str, Any]]] = []
    ctx = _ctx(emitted)
    budget = _RecordingBudget()
    ctx.budget = budget
    ctx.policy = Policy(token_budget=1000)

    executor = LlmExecutor({"openai-compatible": _CancellingProvider()})
    with pytest.raises(asyncio.CancelledError):
        await executor.execute(_node("deepseek-chat"), ctx)

    # 关键：取消路径也回冲了预占（grant, 0）
    assert len(budget.settled) == 1
    assert budget.settled[0][1] == 0
