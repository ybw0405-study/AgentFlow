"""llm 节点记录真实下发的模型名。

画布从 mock 切到真实 provider 后，DSL 里沿用的 mock-* 占位名会被 provider 回落到
服务端默认模型；此时若仍把占位名写进 output 与事件，事后审计就看不出实际用了哪个
模型。这里锁住「声明值 / 实际值」的区分。
"""

from __future__ import annotations

import uuid
from typing import Any

from app.commands.base import CommandResult
from app.dsl.models import LlmConfig, LlmNode
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
