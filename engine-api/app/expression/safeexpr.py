"""受限表达式引擎（§9 安全约束）。

- 基于 simpleeval：默认禁 import、禁下划线属性访问、无内建危险名。
- 白名单函数是唯一可用能力扩展点。
- 绝不使用 eval/exec（CI 以 Ruff S307/S102 双重拦截）。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
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
    "json_get": lambda obj, path: _json_get(unwrap_value(obj), path),
    "to_json": lambda v: json.dumps(unwrap_value(v), ensure_ascii=False),
}


class _Scope:
    """dict 的属性视图：让 `vars.items` 取到变量的值而不是 `dict.items` 方法。

    simpleeval 解析 `a.b` 走 getattr；如果 a 是原生 dict，`items` / `keys` / `values` /
    `get` 这类名字会命中 dict 自带方法，表达式静默取到方法对象：
    变量叫 items 时 loop 直接报 "items_expression must evaluate to a list"，
    condition 则把一个方法当成真值。这里用一个没有这些属性的包装对象避开同名冲突。
    """

    __slots__ = ("_data",)

    def __init__(self, data: Mapping[str, Any]) -> None:
        object.__setattr__(self, "_data", data)

    def __getattr__(self, name: str) -> Any:
        data = object.__getattribute__(self, "_data")
        if name in data:
            return wrap_context(data[name])
        raise AttributeError(name)

    def __getitem__(self, key: str) -> Any:
        return wrap_context(self._data[key])

    def __contains__(self, key: str) -> bool:
        return key in self._data

    def __iter__(self) -> Any:
        return iter(self._data)

    def __len__(self) -> int:
        return len(self._data)

    def __bool__(self) -> bool:
        return bool(self._data)

    def __repr__(self) -> str:
        return repr(self._data)


def wrap_context(value: Any) -> Any:
    """把 dict 递归包成 _Scope，供表达式与模板共用。"""
    if isinstance(value, Mapping):
        return _Scope(value)
    if isinstance(value, list):
        return [wrap_context(v) for v in value]
    return value


def unwrap_value(value: Any) -> Any:
    if isinstance(value, _Scope):
        return object.__getattribute__(value, "_data")
    if isinstance(value, list):
        return [unwrap_value(v) for v in value]
    return value


_LITERAL_ALIASES: dict[str, Any] = {"true": True, "false": False, "null": None, "none": None}


def evaluate(expression: str, names: dict[str, Any]) -> Any:
    scoped = {k: wrap_context(v) for k, v in names.items()}
    # `true` / `false` / `null` 是 Jinja 与 JSON 共有的写法。simpleeval 默认只认 Python 的
    # True / False / None，用户按 JSON 习惯写 `vars.urgent == true` 会拿到 EXPR-EVAL-001。
    scoped = {**_LITERAL_ALIASES, **scoped}
    evaluator = EvalWithCompoundTypes(names=scoped, functions=dict(ALLOWED_FUNCTIONS))
    try:
        return unwrap_value(evaluator.eval(expression))
    except ExpressionError:
        raise
    except Exception as exc: # simpleeval 各类拒绝统一归一
        raise ExpressionError(f"expression {expression!r} rejected: {exc}") from exc
