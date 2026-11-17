"""loop 节点：按 items_expression 迭代执行 body 子图（§8.3 / US-03）。

- 每轮产生独立 nodeKey（loopKey#index），迭代变量注入子图上下文；
- 超限抛 LoopLimitExceededError（失败，已完成的迭代结果保留在事件流）；
- collect 汇总：into 变量名、item 取自每轮 end 输出的字段。
"""

from __future__ import annotations

import asyncio
from typing import Any

from ..dsl.models import LoopNode
from ..errors import LoopLimitExceededError, PermanentNodeError
from ..expression.safeexpr import evaluate
from .base import NodeContext, NodeResult


class LoopExecutor:
    node_type = "loop"

    async def execute(self, node: LoopNode, ctx: NodeContext) -> NodeResult:
        if ctx.subgraph is None:
            raise PermanentNodeError("SUBGRAPH-RUNTIME-001", "loop requires subgraph runtime")
        cfg = node.config
        items = evaluate(cfg.items_expression, ctx.template_context())
        if not isinstance(items, list):
            raise PermanentNodeError("LOOP-ITEMS-001", "items_expression must evaluate to a list")
        if len(items) > cfg.max_iterations:
            raise LoopLimitExceededError(f"loop has {len(items)} items, limit {cfg.max_iterations}")

        sem = asyncio.Semaphore(cfg.concurrency)

        async def one(index: int, item: Any) -> dict[str, Any]:
            async with sem:
                return await ctx.subgraph.run(
                    cfg.body,
                    key_prefix=f"{node.key}#{index}",
                    extra_variables={cfg.item_var: item, cfg.index_var: index},
                )

        results = await asyncio.gather(*[one(i, item) for i, item in enumerate(items)])

        output: dict[str, Any] = {"items": results, "count": len(results)}
        if cfg.collect is not None:
            collected = []
            for r in results:
                collected.append(r.get(cfg.collect.item) if isinstance(r, dict) else None)
            output[cfg.collect.into] = collected
            ctx.variables[cfg.collect.into] = collected
        return NodeResult(
            output=output,
            input_snapshot={"itemsExpression": cfg.items_expression, "count": len(items)},
        )
