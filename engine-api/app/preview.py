"""单节点试跑：把「目标节点 + 它的上游」裁成一张可执行的小图。

为什么不另写一套执行逻辑：试跑的意义在于「和整体运行同一个结果」。裁剪后的图交给
同一个调度器、同一批 executor、同一份预算与错误分类，跑出来的输出就是真实链路的结果；
自己写一遍单节点调用，迟早会和主链路跑偏。

循环 / 并行 / 人工审批不参与裁剪：循环体被拆开后迭代语义就没了，并行分支少一条会得到
错误的聚合结果，审批节点无法在试跑里原地续跑。与其给出一个看起来能跑、结果不一样的
试跑，不如明确拒绝并提示整体运行。
"""

from __future__ import annotations

import copy
from dataclasses import dataclass
from typing import Any

from .errors import PermanentNodeError

# 裁剪会改变语义的结构：直接拒绝，不猜。
UNSUPPORTED_TYPES = frozenset({"loop", "parallel", "human_gate"})

PREVIEW_END_BASE = "preview_end"


@dataclass(frozen=True)
class PreviewPlan:
    dsl: dict[str, Any]
    target: str
    upstream: tuple[str, ...]
    end_key: str


def _fail(code: str, message: str) -> PermanentNodeError:
    return PermanentNodeError(code, message)


def _end_key(nodes: dict[str, Any]) -> str:
    key = PREVIEW_END_BASE
    while key in nodes:
        key += "_"
    return key


def plan_preview(raw: dict[str, Any], node_key: str) -> PreviewPlan:
    """返回裁剪后的 DSL；结构不支持时抛 PermanentNodeError（调用方转 422）。"""
    nodes = {str(n.get("key")): n for n in raw.get("nodes") or [] if n.get("key")}
    if node_key not in nodes:
        raise _fail("PREVIEW-NODE-001", f"节点 '{node_key}' 不在当前草稿里")
    edges = [e for e in raw.get("edges") or [] if e.get("from") and e.get("to")]

    keep: set[str] = {node_key}
    stack = [node_key]
    while stack:
        current = stack.pop()
        for edge in edges:
            source = str(edge["from"])
            if edge["to"] == current and source in nodes and source not in keep:
                keep.add(source)
                stack.append(source)

    if not any(nodes[k].get("type") == "start" for k in keep):
        raise _fail(
            "PREVIEW-GRAPH-001",
            f"节点 '{node_key}' 从 start 不可达，无法试跑",
        )

    blocked = sorted(k for k in keep if nodes[k].get("type") in UNSUPPORTED_TYPES)
    if blocked:
        raise _fail(
            "PREVIEW-STRUCTURE-001",
            "该节点位于循环 / 并行 / 人工审批结构中，试跑会改变执行语义："
            + "、".join(blocked)
            + "；请用「运行」跑完整流程",
        )

    end_key = _end_key(nodes)
    ordered = [k for k in nodes if k in keep]
    sub_nodes = [copy.deepcopy(nodes[k]) for k in ordered]
    sub_nodes.append(
        {
            "key": end_key,
            "type": "end",
            # 结果从目标节点的输出里直接读，这里只需要一个合法的收口。
            "config": {"resultMapping": {"preview": "ok"}},
        }
    )
    sub_edges = [copy.deepcopy(e) for e in edges if str(e["from"]) in keep and str(e["to"]) in keep]
    sub_edges.append({"from": node_key, "to": end_key})

    sub_dsl: dict[str, Any] = {
        "dslVersion": raw.get("dslVersion", "1.0"),
        "variables": copy.deepcopy(raw.get("variables") or {}),
        "nodes": sub_nodes,
        "edges": sub_edges,
    }
    if raw.get("defaults"):
        sub_dsl["defaults"] = copy.deepcopy(raw["defaults"])
    return PreviewPlan(
        dsl=sub_dsl,
        target=node_key,
        upstream=tuple(k for k in ordered if k != node_key),
        end_key=end_key,
    )
