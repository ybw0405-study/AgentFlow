"""节点执行器装配。"""

from __future__ import annotations

from typing import TYPE_CHECKING

import redis.asyncio as aioredis

from ..commands.providers.http_tool import HttpToolConnector
from ..commands.providers.mock_tool import MockToolConnector
from .condition import ConditionExecutor
from .end import EndExecutor
from .human_gate import HumanGateExecutor
from .llm import LlmExecutor, LLMProvider
from .loop import LoopExecutor
from .parallel import ParallelExecutor
from .registry import NodeExecutor
from .start import StartExecutor
from .subworkflow import SubworkflowExecutor
from .tool import ConnectorRegistry, ToolExecutor
from .transform import TransformExecutor

if TYPE_CHECKING:
    from .subworkflow_resolver import SubworkflowResolver


def build_executors(
    redis: aioredis.Redis,
    llm_providers: dict[str, LLMProvider],
    subworkflow_resolver: SubworkflowResolver | None = None,
    connector_gateway: tuple[str, str] | None = None,
) -> dict[str, NodeExecutor]:
    """connector_gateway=(gateway_base, internal_token) 时启用真实连接器。"""
    remote = (
        (lambda cid: HttpToolConnector(connector_gateway[0], connector_gateway[1], cid))
        if connector_gateway
        else None
    )
    connectors = ConnectorRegistry(lambda cid: MockToolConnector(redis, cid), remote)
    return {
        "start": StartExecutor(),
        "end": EndExecutor(),
        "llm": LlmExecutor(llm_providers),
        "tool": ToolExecutor(connectors),
        "condition": ConditionExecutor(),
        "transform": TransformExecutor(),
        "loop": LoopExecutor(),
        "parallel": ParallelExecutor(),
        "human_gate": HumanGateExecutor(),
        "subworkflow": SubworkflowExecutor(subworkflow_resolver)
        if subworkflow_resolver is not None
        else _NoResolverSubworkflow(),
    }


class _NoResolverSubworkflow:
    """未配置 resolver 时占位：执行 subworkflow 节点给出明确错误。"""

    node_type = "subworkflow"

    async def execute(self, node, ctx):
        from ..errors import PermanentNodeError

        raise PermanentNodeError("SUBWF-NORESOLVER-001", "no subworkflow resolver configured")


__all__ = ["LLMProvider", "NodeExecutor", "build_executors"]
