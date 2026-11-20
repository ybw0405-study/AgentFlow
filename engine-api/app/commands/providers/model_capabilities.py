"""模型能力标注：把上游 /models 的零散字段与模型名收敛成前端可直接展示的标签。

为什么放在引擎侧：能力字段只有持有密钥的一侧读得到（部分网关会随 `/models` 一并给出
`context_length`、`supported_parameters` 等），前端不该为了显示几个标签再打一次上游。

三类来源，按可信度从高到低：

1. `upstream`：上游 `/models` 明写的字段，直接采信；
2. `inferred`：模型名里的标记（`-reasoner`、`o3`、`thinking`…），只用于判断「是不是推理型」，
   因为推理型模型会先花 token 思考，直接影响 `maxTokens` 该怎么填；
3. 都没有就只给 id，前端不显示任何标签。

这里刻意不做「家族能力表」：把某家某代的上下文长度写死在代码里，等厂商换代就会变成
一句静默的谎话。拿不到就空着，比猜一个数字安全。
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

# 标签词表：前端按这些字符串出中文标签，新增标签需要前后端一起改，不做自由文本
TAG_REASONING = "reasoning"
TAG_LONG_CONTEXT = "long-context"
TAG_JSON = "json"
TAG_VISION = "vision"

CONTEXT_TAG_THRESHOLD = 100_000
"""到这个量级才算「长上下文」，避免把 8k/32k 也标成长上下文。"""

REASONING_MIN_OUTPUT_TOKENS = 4096
"""推理型模型的最小正文预算，与前端「设为 4096」的建议一致。

思维链与正文共用 max_tokens，1024 常常只够思考、正文为空。这个下限只在
下发前生效，不改写 DSL 里的配置（见 nodes/llm.py），并且会记进事件留痕。
"""

_REASONING_MARKERS = (
    "reasoner",
    "reasoning",
    "thinking",
    "qwq",
    "deepseek-r",
    "o1",
    "o3",
    "o4",
    "r1",
)
"""推理型模型的命名标记。按「分隔符边界」匹配，避免 `gpt-4o-mini` 被 `o1` 误伤。"""

_REASONING_FAMILY_VARIANTS = {
    "deepseek": frozenset({"flash", "v4"}),
}
"""有些家族用变体名而不是 reasoner/r1 表示推理型。

`deepseek-flash` / `deepseek-v4-pro` 的思维链同样占用 max_tokens，但名字里
没有上面任何词元；只回 id、拿不到上游能力字段时就会漏标，界面连「设大一点」
的提示都不给。这里要求家族名与变体名同时出现，避免把别家的 v4 误标。
"""

_CONTEXT_FIELDS = ("context_length", "context_window", "max_context_length", "max_model_len")
_OUTPUT_FIELDS = ("max_output_tokens", "max_completion_tokens", "max_tokens")
_LONG_CONTEXT_NAME_MARKERS = ("128k", "200k", "256k", "1m", "long-context", "longcontext")


@dataclass(frozen=True)
class ModelFacts:
    """单个模型的能力标注。字段缺失一律 None，由前端决定怎么展示。"""

    id: str
    tags: tuple[str, ...] = ()
    context_window: int | None = None
    max_output_tokens: int | None = None
    source: str = "inferred"
    """upstream=上游字段直接给出；inferred=按模型名推断。"""


def _as_int(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    number = int(value)
    return number if number > 0 else None


def _first_int(source: dict[str, Any], names: tuple[str, ...]) -> int | None:
    for name in names:
        found = _as_int(source.get(name))
        if found is not None:
            return found
    return None


def _tokens(model_id: str) -> set[str]:
    """把模型名切成词元，`deepseek-r1-0528` → {deepseek, r1, 0528}。"""
    return {token for token in re.split(r"[^a-z0-9]+", model_id.lower()) if token}


def _is_reasoning_name(model_id: str) -> bool:
    """只认整段词元，`gpt-4o-mini` 不会被 `o1` 误判成推理型。"""
    tokens = _tokens(model_id)
    if tokens & set(_REASONING_MARKERS):
        return True
    return any(
        family in tokens and bool(tokens & variants)
        for family, variants in _REASONING_FAMILY_VARIANTS.items()
    )


def is_reasoning_model(model_id: str) -> bool:
    """执行链路用：只按模型名判断，不依赖上游 /models 清单是否可达。

    与 describe_model 共用同一份词表，避免「界面提示是推理型、下发却按普通
    模型给 1024」这种两套口径。上游明写的字段仍由 describe_model 优先采信。
    """
    return _is_reasoning_name(model_id)


def _supported_parameters(item: dict[str, Any]) -> set[str]:
    raw = item.get("supported_parameters")
    if not isinstance(raw, list):
        return set()
    return {str(entry).lower() for entry in raw if isinstance(entry, str)}


def _input_modalities(item: dict[str, Any]) -> set[str]:
    """输入模态可能写在顶层，也可能挂在 `architecture` 下，两种都认。"""
    for source in (item, item.get("architecture")):
        if not isinstance(source, dict):
            continue
        raw = source.get("input_modalities")
        if isinstance(raw, list):
            return {str(entry).lower() for entry in raw}
    return set()


def _supports_effort(item: dict[str, Any]) -> bool:
    """`effort.supported_levels` 这类「思考强度」开关只有推理型模型才有。"""
    effort = item.get("effort")
    if not isinstance(effort, dict):
        return False
    levels = effort.get("supported_levels")
    return isinstance(levels, list) and bool(levels)


def describe_model(item: dict[str, Any]) -> ModelFacts | None:
    """从 `/models` 的一条记录里提取能力；不是对象或缺 id 就跳过。"""
    model_id = item.get("id")
    if not isinstance(model_id, str) or not model_id.strip():
        return None
    model_id = model_id.strip()

    tags: list[str] = []
    params = _supported_parameters(item)
    modalities = _input_modalities(item)
    if (
        "reasoning" in params
        or "include_reasoning" in params
        or _supports_effort(item)
        or _is_reasoning_name(model_id)
    ):
        tags.append(TAG_REASONING)
    if "response_format" in params:
        tags.append(TAG_JSON)
    if "image" in modalities:
        tags.append(TAG_VISION)

    top_provider = item.get("top_provider")
    max_output = _first_int(item, _OUTPUT_FIELDS)
    if max_output is None and isinstance(top_provider, dict):
        max_output = _first_int(top_provider, ("max_completion_tokens", "max_output_tokens"))

    context_window = _first_int(item, _CONTEXT_FIELDS)
    if context_window is not None and context_window >= CONTEXT_TAG_THRESHOLD:
        tags.append(TAG_LONG_CONTEXT)
    elif any(marker in model_id.lower() for marker in _LONG_CONTEXT_NAME_MARKERS):
        tags.append(TAG_LONG_CONTEXT)

    has_upstream_fields = bool(params or modalities or context_window or max_output)
    return ModelFacts(
        id=model_id,
        tags=tuple(tags),
        context_window=context_window,
        max_output_tokens=max_output,
        source="upstream" if has_upstream_fields else "inferred",
    )
