"""派发契约：控制面 → 引擎的唯一请求模型（§5.3 信任边界）。

请求只含 executionId / 不可变版本快照 / 租户坐标 / 授权连接器列表 /
脱敏后的初始变量 / 预算政策 —— 不得包含任何用户凭据。
"""

from __future__ import annotations

import uuid
from typing import Any

from pydantic import Field

from ..dsl.models import CamelModel


class PolicyIn(CamelModel):
    token_budget: int | None = None
    max_steps: int | None = None
    deadline_ms: int | None = None


class ExecuteRequest(CamelModel):
    execution_id: uuid.UUID
    definition_id: str
    version_id: str
    tenant_id: str
    space_id: str
    triggered_by: str
    idempotency_key: str
    version_snapshot: dict[str, Any]
    authorized_connector_ids: list[str] = Field(default_factory=list)
    initial_variables: dict[str, Any] = Field(default_factory=dict)
    policy: PolicyIn = PolicyIn()
    trace_id: str
    execution_seed: str | None = None
    # 试跑与真实执行共用 dispatcher，但绝不能被崩溃恢复扫描当成
    # 「未跑完的业务执行」续跑（会重复调用模型、重复计费）。该标记随 spec
    # 一起持久化，恢复扫描据此直接强制落终态并清理，不派发任何节点。
    preview: bool = False
