"""transform 节点：Jinja2 模板映射变量（§9，无副作用、不重试）。

target 形如 vars.xxx；输出去掉 vars. 前缀，作为下游可见的输出字典。
"""

from __future__ import annotations

from ..dsl.models import TransformNode
from ..expression.template import render_mapping
from .base import NodeContext, NodeResult


class TransformExecutor:
    node_type = "transform"

    async def execute(self, node: TransformNode, ctx: NodeContext) -> NodeResult:
        rendered = render_mapping(
            {m.target: m.template for m in node.config.mappings}, ctx.template_context()
        )
        output = {k.removeprefix("vars."): v for k, v in rendered.items()}
        # target 形如 vars.xxx：语义是写回变量，供下游节点通过 vars 引用。
        # ctx.variables 是 state.variables 的同一引用（NodeRunner 装配），原地更新即可。
        ctx.variables.update(output)
        return NodeResult(
            output=output,
            input_snapshot={"mappings": {m.target: m.template for m in node.config.mappings}},
        )
