"""子图执行器（loop/parallel 共用）：编译 SubGraph → 拓扑执行 → 聚合输出。

设计要点：
- 复用 NodeRunner 执行每个子图节点（重试/超时/事件/检查点一致）；
- 子图节点 key 前缀化为 "<parent>#<index>#<child>"，事件流可见、可重放；
- 子图节点 key 不进 DSL 校验（NodeKey 正则仅限静态定义），仅作事件 node_key；
- 分支边（condition）在子图内同样生效；未命中分支下游 SKIPPED。
"""

from __future__ import annotations

import asyncio
from typing import TYPE_CHECKING, Any

from ..dsl.compile import compile_dag
from ..dsl.models import AnyNode, SubGraph, WorkflowDsl
from ..errors import PermanentNodeError
from .base import NodeContext

if TYPE_CHECKING:
    from ..engine.executor import NodeRunner
    from ..engine.state import ExecutionState


def _edge_active(edge: Any, state: ExecutionState) -> bool:
    """入边激活判定（与 scheduler 同语义；本地化避免跨层 import 循环）。"""
    src = state.nodes.get(edge.from_)
    if src is None or src.status != "SUCCEEDED":
        return False
    if edge.branch is None:
        return True
    return (src.output or {}).get("branch") == edge.branch


class SubgraphRunner:
    """绑定一次节点执行的上下文，执行其配置里的子图。"""

    def __init__(
        self,
        node_runner: NodeRunner,
        state: ExecutionState,
        ctx: NodeContext,
        dsl: WorkflowDsl,
        *,
        depth: int = 0,
    ) -> None:
        self._runner = node_runner
        self._state = state
        self._ctx = ctx
        self._dsl = dsl
        self._depth = depth
        if depth >= 8:
            raise PermanentNodeError("SUBGRAPH-DEPTH-001", "subgraph nesting exceeds 8 levels")

    def _prefixed(self, node: AnyNode, prefix: str) -> AnyNode:
        return node.model_copy(update={"key": f"{prefix}#{node.key}"})

    async def run(
        self,
        subgraph: SubGraph,
        *,
        key_prefix: str,
        extra_variables: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """执行子图，返回 end 节点的 result 映射；无 end 时返回最后一层输出。"""
        if any(n.type == "human_gate" for n in subgraph.nodes):
            # 子图节点挂起后无法原地续跑（裁决会重跑整段子图）；编译期已拦截，
            # 这里兜住 subworkflow 引用的外部 DSL（其校验发生在别的租户上下文）。
            raise PermanentNodeError(
                "SUBGRAPH-GATE-001", "human_gate is not supported inside a subgraph"
            )
        nodes = [self._prefixed(n, key_prefix) for n in subgraph.nodes]
        edges = [
            e.model_copy(
                update={
                    "from_": f"{key_prefix}#{e.from_}",
                    "to": f"{key_prefix}#{e.to}",
                }
            )
            for e in subgraph.edges
        ]
        sub_dsl = WorkflowDsl(
            dslVersion="1.0",
            # variables 是 spec 定义区；迭代变量的值只注入 ctx.variables（下方）
            variables=dict(self._dsl.variables),
            defaults=self._dsl.defaults,
            nodes=nodes,
            edges=edges,
        )
        dag = compile_dag(sub_dsl)

        # 每次子图执行用独立变量副本：并发迭代（loop concurrency>1）互不干扰，
        # 且子图内 transform 的写回只作用于本副本，不污染主执行变量（§8.3）。
        local_vars = {**self._ctx.variables, **(extra_variables or {})}
        # 子图节点以原始 key 互相引用（模板里的 nodes.draft 等），需剥离前缀的视图。
        local_outputs: dict[str, dict[str, Any]] = {}
        prefix = f"{key_prefix}#"
        for layer in dag.layers:
            ready: list[AnyNode] = []
            for key in layer:
                nr = self._state.nodes.get(key)
                if nr is not None and nr.status in ("SUCCEEDED", "SKIPPED"):
                    continue # 重放：已完成子图节点不重跑
                in_edges = dag.in_edges.get(key, [])
                if not in_edges:
                    ready.append(dag.nodes[key])
                    continue
                settled = all(
                    (
                        self._state.nodes.get(e.from_).status
                        if e.from_ in self._state.nodes
                        else "PENDING"
                    )
                    in ("SUCCEEDED", "SKIPPED")
                    for e in in_edges
                )
                if not settled:
                    continue
                if any(_edge_active(e, self._state) for e in in_edges):
                    ready.append(dag.nodes[key])
                else:
                    await self._ctx.emit(
                        "NodeSkipped",
                        {"reason": "BRANCH_NOT_TAKEN_OR_UPSTREAM_SKIPPED"},
                        node_key=key,
                    )
            await asyncio.gather(
                *[
                    self._runner.run(
                        node=n,
                        dag=dag,
                        state=self._state,
                        dsl=sub_dsl,
                        execution_id=self._ctx.execution_id,
                        seed=self._ctx.seed,
                        trace_id=self._ctx.trace_id,
                        policy=self._ctx.policy,
                        budget=self._ctx.budget,
                        authorized_connector_ids=self._ctx.authorized_connector_ids,
                        space_id=self._ctx.space_id,
                        variables_override=local_vars,
                        outputs_override=dict(local_outputs),
                    )
                    for n in ready
                ]
            )
            # 收集本层完成节点，剥离前缀后供下游以原始 key 引用
            for key in layer:
                nr = self._state.nodes.get(key)
                if nr is not None and nr.status == "SUCCEEDED" and nr.output is not None:
                    original = key[len(prefix) :] if key.startswith(prefix) else key
                    local_outputs[original] = nr.output
            # 子图内 transform 对局部变量的写回，同步进 local_vars 供后续层引用
            local_vars.update({k: v for k, v in local_outputs.items() if k in local_vars})

        # 只收成功的 end：子图内也可能有多个 end（分支各自收口），
        # 取第一个定义会把没跑到的那个当成结果。
        merged: dict[str, Any] = {}
        found_end = False
        for end_key in dag.end_keys():
            nr = self._state.nodes.get(end_key)
            if nr is None or nr.status != "SUCCEEDED":
                continue
            found_end = True
            out = nr.output or {}
            produced = out.get("result", out)
            if isinstance(produced, dict):
                merged.update(produced)
        if found_end:
            return merged
        tail: dict[str, Any] = {}
        for layer in reversed(dag.layers):
            for k in layer:
                nr = self._state.nodes.get(k)
                if nr is not None and nr.status == "SUCCEEDED" and nr.output:
                    tail.update(nr.output)
            if tail:
                break
        return tail

    def with_depth(self, depth: int) -> SubgraphRunner:
        return SubgraphRunner(self._runner, self._state, self._ctx, self._dsl, depth=depth)
