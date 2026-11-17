"""受限表达式引擎（§9 安全约束）。

- 基于 simpleeval：默认禁 import、禁下划线属性访问、无内建危险名。
- 白名单函数是唯一可用能力扩展点。
- 绝不使用 eval/exec（CI 以 Ruff S307/S102 双重拦截）。
"""

from __future__ import annotations

import json
from typing import Any

from simpleeval import EvalWithCompoundTypes

from ..errors import ExpressionError


def _json_get(obj: Any, path: str) -> Any:
    """极简 JSONPath：$.a.b[0].c"""
    if not path.startswith("$"):
        raise ExpressionError(f"json_get path must start with '$': {path!r}")
    cur = obj
    token = ""
    i = 1
    parts: list[Any] = []
    while i < len(path):
        ch = path[i]
        if ch == ".":
            if token:
                parts.append(token)
                token = ""
        elif ch == "[":
            if token:
                parts.append(token)
                token = ""
            j = path.index("]", i)
            parts.append(int(path[i + 1 : j]))
            i = j
        else:
            token += ch
        i += 1
    if token:
        parts.append(token)
    try:
        for p in parts:
            cur = (
                cur[p]
                if isinstance(p, int)
                else cur[p]
                if isinstance(cur, dict)
                else getattr(cur, p)
            )
        return cur
    except (KeyError, IndexError, TypeError, AttributeError) as exc:
        raise ExpressionError(f"json_get {path!r} failed: {exc}") from exc


ALLOWED_FUNCTIONS: dict[str, Any] = {
    "len": len,
    "upper": lambda s: str(s).upper(),
    "lower": lambda s: str(s).lower(),
    "contains": lambda haystack, needle: needle in haystack,
    "startsWith": lambda s, p: str(s).startswith(str(p)),
    "endsWith": lambda s, p: str(s).endswith(str(p)),
    "int": int,
    "float": float,
    "str": str,
    "bool": bool,
    "abs": abs,
    "min": min,
    "max": max,
    "round": round,
    "json_get": _json_get,
    "to_json": lambda v: json.dumps(v, ensure_ascii=False),
}


def evaluate(expression: str, names: dict[str, Any]) -> Any:
    evaluator = EvalWithCompoundTypes(names=names, functions=dict(ALLOWED_FUNCTIONS))
    try:
        return evaluator.eval(expression)
    except ExpressionError:
        raise
    except Exception as exc:  # simpleeval 各类拒绝统一归一
        raise ExpressionError(f"expression {expression!r} rejected: {exc}") from exc
