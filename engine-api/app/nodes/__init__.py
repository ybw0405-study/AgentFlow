"""节点执行器装配。"""

from __future__ import annotations

from typing import TYPE_CHECKING

import redis.asyncio as aioredis

from ..commands.providers.connector_llm import ConnectorLlmFactory
from ..commands.providers.http_tool import HttpToolConnector
from ..commands.providers.knowledge_search import KnowledgeSearchClient
from ..commands.providers.mock_tool import MockToolConnector
from .condition import ConditionExecutor
from .end import EndExecutor
from .human_gate import HumanGateExecutor
from .llm import LlmExecutor, LLMProvider
from .loop import LoopExecutor
from .parallel import ParallelExecutor
from .registry import NodeExecutor
from .retrieval import RetrievalExecutor
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
    knowledge_gateway: tuple[str, str] | None = None,
) -> dict[str, NodeExecutor]:
    """connector_gateway=(gateway_base, internal_token) 时启用真实连接器与模型连接；
    knowledge_gateway 同源，启用文档检索节点。两者缺省时对应节点给出明确错误而非静默回落。
    """
    remote = (
        (lambda cid: HttpToolConnector(connector_gateway[0], connector_gateway[1], cid))
        if connector_gateway
        else None
    )
    connector_llms = (
        ConnectorLlmFactory(
            gateway_base_url=connector_gateway[0], internal_token=connector_gateway[1]
        )
        if connector_gateway
        else None
    )
    connectors = ConnectorRegistry(lambda cid: MockToolConnector(redis, cid), remote)
    knowledge = (
        KnowledgeSearchClient(knowledge_gateway[0], knowledge_gateway[1])
        if knowledge_gateway
        else None
    )
    return {
        "start": StartExecutor(),
        "end": EndExecutor(),
        "llm": LlmExecutor(llm_providers, connector_llms),
        "retrieval": RetrievalExecutor(knowledge),
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
