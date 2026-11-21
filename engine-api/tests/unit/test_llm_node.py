"""llm 节点：真实模型名、推理型 maxTokens 下限与下发留痕。

画布从 mock 切到真实 provider 后，DSL 里沿用的 mock-* 占位名会被 provider 回落到
服务端默认模型；此时若仍把占位名写进 output 与事件，事后审计就看不出实际用了哪个
模型。这里锁住「声明值 / 实际值」的区分，以及推理型模型的输出预算下限。
"""

from __future__ import annotations

import uuid
from typing import Any

import pytest

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

    async def reserve(self, _execution_id: Any, _budget: int, requested: int) -> int:
        self.requested = requested
        return requested

    async def settle(self, _execution_id: Any, _grant: int, _actual: int) -> None:
        return None


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
