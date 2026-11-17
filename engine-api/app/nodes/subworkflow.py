"""subworkflow 节点：调用另一个已发布工作流的不可变 DSL 快照（§9 / P1）。

- version_policy=LATEST_PUBLISHED：经 gateway 查 current 版本；FIXED：按 versionId 查；
- input/output mapping：父变量 → 子初始变量、子结果 → 父输出；
- 子执行复用 SubgraphRunner，节点 key 前缀化，事件可追溯、可重放。
"""

from __future__ import annotations

from ..dsl.models import SubGraph, SubworkflowNode
from ..errors import PermanentNodeError
from ..expression.template import render_mapping
from .base import NodeContext, NodeResult
from .subworkflow_resolver import SubworkflowResolver


class SubworkflowExecutor:
    node_type = "subworkflow"

    def __init__(self, resolver: SubworkflowResolver) -> None:
        self._resolver = resolver

    async def execute(self, node: SubworkflowNode, ctx: NodeContext) -> NodeResult:
        if ctx.subgraph is None:
            raise PermanentNodeError(
                "SUBGRAPH-RUNTIME-001", "subworkflow requires subgraph runtime"
            )
        cfg = node.config
        dsl = await self._resolver.resolve(
            definition_id=cfg.definition_id,
            space_id=ctx.space_id,
            version_id=cfg.version_id if cfg.version_policy == "FIXED" else None,
        )
        tctx = ctx.template_context()
        inputs = render_mapping(cfg.input_mapping, tctx) if cfg.input_mapping else {}

        sub = SubGraph.model_validate({"nodes": dsl["nodes"], "edges": dsl.get("edges", [])})
        result = await ctx.subgraph.run(sub, key_prefix=f"{node.key}#run", extra_variables=inputs)

        if cfg.output_mapping:
            # 子结果作为 vars 注入，渲染 output mapping
            scoped = {**ctx.variables, **result}
            rendered = render_mapping(cfg.output_mapping, {"vars": scoped, "nodes": {}})
            output = {k.removeprefix("vars."): v for k, v in rendered.items()}
        else:
            output = {"result": result}
        # target 形如 vars.xxx：写回父变量供下游引用
        ctx.variables.update(output)
        return NodeResult(
            output=output,
            input_snapshot={"definitionId": cfg.definition_id, "versionPolicy": cfg.version_policy},
        )
