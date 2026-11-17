"""condition 节点：受限表达式求命中分支（§9 安全约束，禁任意代码执行）。

输出契约：{"branch": <命中的 case.name | else_branch>, "matched": <bool>}。
调度器读取 branch 与 edge.branch 比对，未命中分支的下游标记 SKIPPED（US-03）。
"""

from __future__ import annotations

from ..dsl.models import ConditionNode
from ..errors import ExpressionError
from ..expression.safeexpr import evaluate
from .base import NodeContext, NodeResult


class ConditionExecutor:
    node_type = "condition"

    async def execute(self, node: ConditionNode, ctx: NodeContext) -> NodeResult:
        names = ctx.template_context()
        for case in node.config.cases:
            try:
                hit = evaluate(case.when, names)
            except ExpressionError:
                raise
            if hit:
                return NodeResult(
                    output={"branch": case.name, "matched": True},
                    input_snapshot={"when": case.when, "result": True},
                )
        return NodeResult(
            output={"branch": node.config.else_branch, "matched": False},
            input_snapshot={
                "cases": [c.when for c in node.config.cases],
                "elseBranch": node.config.else_branch,
                "result": False,
            },
        )
