"""节点执行器注册表。"""

from __future__ import annotations

from typing import Protocol

from ..dsl.models import AnyNode
from .base import NodeContext, NodeResult


class NodeExecutor(Protocol):
    node_type: str

    async def execute(self, node: AnyNode, ctx: NodeContext) -> NodeResult: ...
