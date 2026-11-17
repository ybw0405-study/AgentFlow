"""end 节点：按 resultMapping 生成最终结果。"""

from __future__ import annotations

from ..dsl.models import EndNode
from ..expression.template import render_mapping
from .base import NodeContext, NodeResult


class EndExecutor:
    node_type = "end"

    async def execute(self, node: EndNode, ctx: NodeContext) -> NodeResult:
        result = render_mapping(node.config.result_mapping, ctx.template_context())
        return NodeResult(
            output={"result": result},
            input_snapshot={"resultMapping": node.config.result_mapping},
        )
