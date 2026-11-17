"""start 节点：校验并合并初始变量（类型/required/default）。"""

from __future__ import annotations

from typing import Any

from ..dsl.models import StartNode
from ..errors import PermanentNodeError
from .base import NodeContext, NodeResult


def _type_ok(value: Any, declared: str) -> bool:
    if declared == "string":
        return isinstance(value, str)
    if declared == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if declared == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if declared == "boolean":
        return isinstance(value, bool)
    if declared == "object":
        return isinstance(value, dict)
    if declared == "array":
        return isinstance(value, list)
    return False


class StartExecutor:
    node_type = "start"

    async def execute(self, node: StartNode, ctx: NodeContext) -> NodeResult:
        merged: dict[str, Any] = {}
        for name, spec in ctx.variables_spec.items():
            if name in ctx.variables and ctx.variables[name] is not None:
                value = ctx.variables[name]
            elif spec.default is not None:
                value = spec.default
            elif spec.required:
                raise PermanentNodeError("EXEC-INPUT-001", f"missing required variable '{name}'")
            else:
                value = None
            if value is not None and not _type_ok(value, spec.type):
                raise PermanentNodeError(
                    "EXEC-INPUT-001", f"variable '{name}' expects type '{spec.type}'"
                )
            merged[name] = value
        # 未声明的入参按不可信数据透传（模板中可见但引用检查已在编译期保证一致性）
        for name, value in ctx.variables.items():
            merged.setdefault(name, value)
        return NodeResult(
            output={"variables": merged},
            input_snapshot={"variables": ctx.variables},
        )
