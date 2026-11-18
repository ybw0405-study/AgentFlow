"""模板渲染：Jinja2 沙箱。

- SandboxedEnvironment：禁内部属性访问与不安全操作。
- StrictUndefined：引用不存在的变量即 EXPR-EVAL-001（宁错不静默）。
- autoescape=False：渲染目标是提示词/参数等数据文本，HTML 转义会破坏内容；
  提示词注入的防线在沙箱与「变量内容按不可信数据处理」的约定上，不在转义。
- 渲染结果长度封顶：`{{ }}` 内可写循环表达式，上游若塞入超大变量会把单个节点
  的输出放大到内存与下游模型都承受不了，这里直接判为表达式错误。
"""

from __future__ import annotations

from typing import Any

from jinja2 import StrictUndefined
from jinja2.sandbox import SandboxedEnvironment

from ..errors import ExpressionError

# 与事件 payload 上限（256KB）对齐：渲染结果本就是节点输出的来源
MAX_RENDERED_CHARS = 256 * 1024

_env = SandboxedEnvironment(undefined=StrictUndefined, autoescape=False)


def render_template(template: str, context: dict[str, Any]) -> str:
    try:
        rendered = _env.from_string(template).render(**context)
    except Exception as exc:
        raise ExpressionError(f"template render failed: {exc}") from exc
    if len(rendered) > MAX_RENDERED_CHARS:
        raise ExpressionError(
            f"template output too large: {len(rendered)} chars > {MAX_RENDERED_CHARS}"
        )
    return rendered


def render_mapping(mapping: dict[str, str], context: dict[str, Any]) -> dict[str, Any]:
    return {k: render_template(v, context) for k, v in mapping.items()}
