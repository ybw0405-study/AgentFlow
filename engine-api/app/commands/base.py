"""Command 对象（§8.2.3）：一切外部副作用的统一描述与幂等键派生。"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class CommandKind(StrEnum):
    LLM = "LLM"
    TOOL = "TOOL"
    RETRIEVAL = "RETRIEVAL"


@dataclass
class CommandResult:
    ok: bool
    data: dict[str, Any] | None = None
    error_code: str | None = None
    transient: bool = False
    token_usage: int = 0
    latency_ms: int = 0
    extra: dict[str, Any] = field(default_factory=dict)


def make_call_id(execution_id: uuid.UUID | str, node_key: str, attempt: int) -> str:
    """确定性 callId：重放/崩溃恢复时同一逻辑调用永远得到同一 callId。"""
    return f"{execution_id}/{node_key}/{attempt}"


def digest_of(obj: Any) -> str:
    raw = json.dumps(obj, ensure_ascii=False, sort_keys=True, default=str)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()
