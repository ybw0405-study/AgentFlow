"""执行事件类型目录（architecture.md §5.2）。扩展事件（标记为 extra）均有条款依据。"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from typing import Any


class EventType(StrEnum):
    # 执行级
    EXECUTION_CREATED = "ExecutionCreated"
    EXECUTION_STARTED = "ExecutionStarted"
    EXECUTION_COMPLETED = "ExecutionCompleted"
    EXECUTION_FAILED = "ExecutionFailed"
    EXECUTION_TIMED_OUT = "ExecutionTimedOut"
    EXECUTION_CANCELLED = "ExecutionCancelled"
    CHECKPOINT_CREATED = "CheckpointCreated"
    # 节点级
    NODE_SCHEDULED = "NodeScheduled"
    NODE_STARTED = "NodeStarted"
    NODE_COMPLETED = "NodeCompleted"
    NODE_FAILED = "NodeFailed"
    NODE_RETRY_SCHEDULED = "NodeRetryScheduled"
    NODE_SKIPPED = "NodeSkipped"
    NODE_SUSPENDED = "NodeSuspended"
    GATE_SUSPENDED = "GateSuspended"
    GATE_RESOLVED = "GateResolved"
    # 命令级（§8.2.3 命令与结果成对落事件）
    COMMAND_ISSUED = "CommandIssued"
    COMMAND_COMPLETED = "CommandCompleted"
    COMMAND_FAILED = "CommandFailed"


TERMINAL_EVENTS: frozenset[EventType] = frozenset(
    {
        EventType.EXECUTION_COMPLETED,
        EventType.EXECUTION_FAILED,
        EventType.EXECUTION_TIMED_OUT,
        EventType.EXECUTION_CANCELLED,
    }
)

EXECUTION_STATUS_OF_EVENT: dict[EventType, str] = {
    EventType.EXECUTION_CREATED: "PENDING",
    EventType.EXECUTION_STARTED: "RUNNING",
    EventType.EXECUTION_COMPLETED: "SUCCEEDED",
    EventType.EXECUTION_FAILED: "FAILED",
    EventType.EXECUTION_TIMED_OUT: "TIMED_OUT",
    EventType.EXECUTION_CANCELLED: "CANCELLED",
}


@dataclass(frozen=True)
class Event:
    seq: int
    execution_id: uuid.UUID
    event_type: EventType
    node_key: str | None
    payload: dict[str, Any]
    aggregate_version: int
    created_at: datetime
    extra: dict[str, Any] = field(default_factory=dict, compare=False)

    def to_dict(self) -> dict[str, Any]:
        return {
            "seq": self.seq,
            "executionId": str(self.execution_id),
            "eventType": str(self.event_type),
            "nodeKey": self.node_key,
            "payload": self.payload,
            "aggregateVersion": self.aggregate_version,
            "createdAt": self.created_at.isoformat(),
        }

    def payload_json(self) -> str:
        return json.dumps(self.payload, ensure_ascii=False, default=str)
