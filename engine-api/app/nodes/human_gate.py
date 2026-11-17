"""human_gate 节点：只负责「挂起」，裁决发生在控制面（gate API）。

纪律：
- 节点不写事件、不消耗 token：抛 GateSuspendedSignal，由 NodeRunner 统一落
  NodeSuspended + GateSuspended（事件写入集中在执行器，节点保持纯函数）。
- 审批人快照、提示词在挂起瞬间冻结（模板此时渲染），避免「审批人看到的」与
  「最终执行的分支」因变量后续变化而不一致。
- 超时只是 deadline 的声明；真正的超时裁决由 Dispatcher 的 gate 扫描器做，
  保证进程崩溃重启后超时仍然生效（事件库是唯一真相）。
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from ..dsl.models import HumanGateNode
from ..errors import GateSuspendedSignal
from ..expression.template import render_template
from .base import NodeContext, NodeResult


class HumanGateExecutor:
    node_type = "human_gate"

    async def execute(self, node: HumanGateNode, ctx: NodeContext) -> NodeResult:
        cfg = node.config
        timeout_at = datetime.now(UTC) + timedelta(milliseconds=cfg.timeout_ms)
        payload: dict[str, Any] = {
            "approversRule": cfg.approvers.model_dump(
                mode="json", by_alias=True, exclude_none=True
            ),
            "prompt": render_template(cfg.prompt_template, ctx.template_context()),
            "timeoutMs": cfg.timeout_ms,
            "timeoutAt": timeout_at.isoformat(),
            "onTimeout": cfg.on_timeout.action,
            "defaultBranch": cfg.on_timeout.default_branch,
        }
        raise GateSuspendedSignal(payload)
