"""parallel 节点：并发执行多个 branch 子图（§8.3 / US-03）。

- join=all：等待全部分支成功，合并输出（分支名 → 输出）；
- join=first_success：按完成顺序取首个成功分支，其余取消（已发出的 Command 按
  execution deadline 收尾，不回滚已完成副作用）。
"""

from __future__ import annotations

import asyncio
from typing import Any

from ..dsl.models import ParallelNode
from ..errors import PermanentNodeError
from .base import NodeContext, NodeResult


class ParallelExecutor:
    node_type = "parallel"

    async def execute(self, node: ParallelNode, ctx: NodeContext) -> NodeResult:
        if ctx.subgraph is None:
            raise PermanentNodeError("SUBGRAPH-RUNTIME-001", "parallel requires subgraph runtime")
        cfg = node.config
        sem = asyncio.Semaphore(cfg.max_concurrency)

        async def one(name: str) -> tuple[str, dict[str, Any], Exception | None]:
            async with sem:
                try:
                    out = await ctx.subgraph.run(
                        cfg.branches[name], key_prefix=f"{node.key}#{name}"
                    )
                    return name, out, None
                except Exception as exc:  # 分支失败作为结果处理，由 join 策略裁决
                    return name, {}, exc

        tasks = [asyncio.create_task(one(name)) for name in cfg.branches]
        results = await asyncio.gather(*tasks)

        if cfg.join == "first_success":
            for name, out, err in results:
                if err is None:
                    return NodeResult(
                        output={"branch": name, "result": out, "merged": {name: out}},
                        input_snapshot={"join": "first_success", "branch": name},
                    )
            failed = "; ".join(f"{n}: {e}" for n, _, e in results if e is not None)
            raise PermanentNodeError("PARALLEL-ALL-FAILED", f"all branches failed: {failed[:300]}")

        errors = [(n, e) for n, _, e in results if e is not None]
        if errors:
            first = errors[0]
            raise PermanentNodeError(
                "PARALLEL-BRANCH-FAILED", f"branch '{first[0]}' failed: {first[1]}"
            )
        merged = {name: out for name, out, _ in results}
        return NodeResult(
            output={"merged": merged, "result": merged},
            input_snapshot={"join": "all", "branches": list(cfg.branches)},
        )
