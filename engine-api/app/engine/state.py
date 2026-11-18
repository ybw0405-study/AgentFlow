"""执行聚合的内存状态。事件是唯一真相，状态只是事件的有序折叠（§6.3、§8.2）。"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from ..events.types import Event, EventType


@dataclass
class NodeRuntime:
    status: str = "PENDING"
    attempt: int = 0
    output: dict[str, Any] | None = None
    token_used: int = 0
    error_code: str | None = None
    retry_count: int = 0


@dataclass
class ExecutionState:
    execution_id: uuid.UUID
    seed: str
    status: str = "PENDING"
    version: int = 0
    variables: dict[str, Any] = field(default_factory=dict)
    nodes: dict[str, NodeRuntime] = field(default_factory=dict)
    token_used: int = 0
    steps_used: int = 0
    result: dict[str, Any] | None = None
    error_code: str | None = None
    events_since_checkpoint: int = 0
    lease_lost: bool = False  # 租约丢失标记：置位后禁止再写事件（§8.5）
    # ExecutionCreated 的落库时间。deadline 必须按「执行创建」而非「本次 worker 启动」
    # 计算，否则崩溃恢复会把总超时窗口重置，长跑流程可被无限续命。
    created_at: datetime | None = None

    def node(self, key: str) -> NodeRuntime:
        return self.nodes.setdefault(key, NodeRuntime())

    def completed_outputs(self) -> dict[str, dict[str, Any]]:
        return {
            k: n.output
            for k, n in self.nodes.items()
            if n.status == "SUCCEEDED" and n.output is not None
        }

    def node_statuses(self) -> dict[str, str]:
        return {k: n.status for k, n in self.nodes.items()}

    def apply(self, event: Event) -> None:
        self.version = event.aggregate_version
        if event.event_type == EventType.CHECKPOINT_CREATED:
            self.events_since_checkpoint = 0
            # 恢复时变量必须从 checkpoint 快照复原：transform/loop 改过的变量
            # 不能因为「只重放事件类型」而丢失（否则续跑会静默用回初始值）。
            # 就地更新而非重新绑定：NodeContext 持有 self.variables 的引用，
            # 重新绑定会让进行中的节点把结果写进旧字典（子工作流映射会丢）。
            snapshot = event.payload.get("variablesSnapshot")
            # snapshot is self.variables 的兜底：payload 若与聚合状态共享同一字典，
            # clear() 会先清空快照本身，复原就变成「把变量抹成空」。
            if isinstance(snapshot, dict) and snapshot is not self.variables:
                self.variables.clear()
                self.variables.update(snapshot)
        else:
            self.events_since_checkpoint += 1

        et, p = event.event_type, event.payload
        if et == EventType.EXECUTION_CREATED:
            self.status = "PENDING"
            self.seed = p.get("executionSeed", self.seed)
            self.created_at = event.created_at
        elif et == EventType.EXECUTION_STARTED:
            self.status = "RUNNING"
        elif et == EventType.EXECUTION_COMPLETED:
            self.status = "SUCCEEDED"
            self.result = p.get("result")
            self.token_used = p.get("tokenUsed", self.token_used)
            self.steps_used = p.get("stepsUsed", self.steps_used)
        elif et == EventType.EXECUTION_FAILED:
            self.status = "FAILED"
            self.error_code = p.get("errorCode")
        elif et == EventType.EXECUTION_TIMED_OUT:
            self.status = "TIMED_OUT"
            self.error_code = "EXEC-DEADLINE_EXCEEDED"
        elif et == EventType.EXECUTION_CANCELLED:
            self.status = "CANCELLED"
        elif event.node_key is not None:
            nr = self.node(event.node_key)
            if et == EventType.NODE_SCHEDULED:
                nr.status = "SCHEDULED"
                nr.attempt = p.get("attempt", 1)
            elif et == EventType.NODE_STARTED:
                nr.status = "RUNNING"
                nr.attempt = p.get("attempt", nr.attempt)
            elif et == EventType.NODE_COMPLETED:
                nr.status = "SUCCEEDED"
                nr.output = p.get("output")
                nr.token_used += p.get("tokenUsed", 0)
                self.token_used += p.get("tokenUsed", 0)
                self.steps_used += 1
            elif et == EventType.NODE_FAILED:
                nr.status = "FAILED"
                nr.error_code = p.get("errorCode")
            elif et == EventType.NODE_RETRY_SCHEDULED:
                nr.retry_count += 1
            elif et == EventType.NODE_SKIPPED:
                nr.status = "SKIPPED"
            elif et in (EventType.NODE_SUSPENDED, EventType.GATE_SUSPENDED):
                nr.status = "SUSPENDED"
