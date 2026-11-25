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
                except Exception as exc: # 分支失败作为结果处理，由 join 策略裁决
                    return name, {}, exc

        tasks = [asyncio.create_task(one(name)) for name in cfg.branches]
        if cfg.join == "first_success":
            return await _first_success(tasks)
        results = await asyncio.gather(*tasks)

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


async def _first_success(tasks: list[asyncio.Task]) -> NodeResult:
    """按完成顺序取首个成功分支，其余 cancel；全部失败才报错。

    与 join=all 不同：这里用 asyncio.wait(FIRST_COMPLETED) 逐轮收割，而不是
    gather 等全部分支跑完再按定义顺序取结果。首个成功即返回并取消仍在跑的分支，
    避免白付 LLM 计费；失败分支只记一笔诊断，继续等下一个成功。已发出的
    Command 不回滚，按 docstring 语义由 execution deadline 收尾。
    """
    pending: set[asyncio.Task] = set(tasks)
    failures: list[tuple[str, Exception]] = []
    try:
        while pending:
            done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                name, out, err = task.result()
                if err is None:
                    return NodeResult(
                        output={"branch": name, "result": out, "merged": {name: out}},
                        input_snapshot={"join": "first_success", "branch": name},
                    )
                failures.append((name, err))
    finally:
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
    failed = "; ".join(f"{n}: {e}" for n, e in failures)
    raise PermanentNodeError("PARALLEL-ALL-FAILED", f"all branches failed: {failed[:300]}")
