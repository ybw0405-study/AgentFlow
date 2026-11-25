"""节点执行上下文与结果。节点执行器只发 Command、返回 NodeResult，不直接做副作用。"""

from __future__ import annotations

import hashlib
import random
import time
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from ..dsl.models import VariableSpec
    from ..events.types import Event
    from ..governance.budget import BudgetLedger
    from .subgraph import SubgraphRunner

EmitFn = Callable[..., Awaitable["Event"]] # (event_type, payload, *, node_key=None)


@dataclass(frozen=True)
class Policy:
    token_budget: int | None = None
    max_steps: int | None = None
    deadline_ms: int | None = None


@dataclass
class NodeResult:
    output: dict[str, Any]
    input_snapshot: dict[str, Any] | None = None
    token_used: int = 0


@dataclass
class NodeContext:
    execution_id: uuid.UUID
    node_key: str
    attempt: int
    seed: str
    trace_id: str
    variables: dict[str, Any]
    variables_spec: dict[str, VariableSpec]
    outputs: dict[str, dict[str, Any]] # 已完成节点 key → output
    node_statuses: dict[str, str]
    policy: Policy
    budget: BudgetLedger | None
    emit: EmitFn
    authorized_connector_ids: frozenset[str] = field(default_factory=frozenset)
    space_id: str = ""
    now: Callable[[], float] = time.monotonic
    # 子图执行入口（loop/parallel/subworkflow 用）；由 NodeRunner 装配，普通节点为 None
    subgraph: SubgraphRunner | None = None

    def template_context(self) -> dict[str, Any]:
        return {
            "vars": self.variables,
            "nodes": {
                k: {"output": v, "status": self.node_statuses.get(k, "UNKNOWN")}
                for k, v in self.outputs.items()
            },
        }

    def derived_seed(self) -> str:
        """确定性随机种子：executionSeed + nodeKey + attempt（§8.2.2）。"""
        return hashlib.sha256(f"{self.seed}|{self.node_key}|{self.attempt}".encode()).hexdigest()

    def rng(self) -> random.Random:
        return random.Random(self.derived_seed()) # noqa: S311
