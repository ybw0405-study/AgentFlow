"""单节点试跑的裁剪规则。

试跑必须与整体执行同源，所以这里只验证「裁剪出的小图能表达同样的上游语义」，
真正的执行结果由 e2e/test_node_preview.py 在真实服务上验收。
"""

from __future__ import annotations

import pytest

from app.errors import PermanentNodeError
from app.preview import plan_preview

VARIABLES = {"question": {"type": "string", "required": True}}


def _dsl(nodes, edges, variables=None):
    return {
        "dslVersion": "1.0",
        "variables": variables or VARIABLES,
        "nodes": nodes,
        "edges": edges,
    }


def _node(key, node_type="transform", config=None):
    return {"key": key, "type": node_type, "config": config or {}}


LINEAR = _dsl(
    [
        _node("start", "start"),
        _node("draft", "llm", {"model": "m", "promptTemplate": "{{ vars.question }}"}),
        _node(
            "polish",
            "llm",
            {"model": "m", "promptTemplate": "{{ nodes.draft.output.text }}"},
        ),
        _node("answer", "end", {"resultMapping": {"answer": "{{ nodes.polish.output.text }}"}}),
    ],
    [
        {"from": "start", "to": "draft"},
        {"from": "draft", "to": "polish"},
        {"from": "polish", "to": "answer"},
    ],
)


def test_keeps_upstream_and_drops_downstream():
    plan = plan_preview(LINEAR, "draft")

    keys = [n["key"] for n in plan.dsl["nodes"]]
    assert keys == ["start", "draft", plan.end_key]
    assert plan.upstream == ("start",)
    assert {"from": "draft", "to": plan.end_key} in plan.dsl["edges"]


def test_preview_end_is_a_real_end_node():
    plan = plan_preview(LINEAR, "polish")

    end = next(n for n in plan.dsl["nodes"] if n["key"] == plan.end_key)
    assert end["type"] == "end"
    assert end["config"]["resultMapping"]


def test_end_key_avoids_collision_with_existing_node():
    dsl = _dsl(
        [
            _node("start", "start"),
            _node("preview_end", "transform"),
            _node("answer", "end", {"resultMapping": {"a": "b"}}),
        ],
        [
            {"from": "start", "to": "preview_end"},
            {"from": "preview_end", "to": "answer"},
        ],
    )

    plan = plan_preview(dsl, "start")
    assert plan.end_key == "preview_end_"
    assert len({n["key"] for n in plan.dsl["nodes"]}) == len(plan.dsl["nodes"])


def test_missing_node_is_rejected():
    with pytest.raises(PermanentNodeError) as exc:
        plan_preview(LINEAR, "nope")
    assert exc.value.code == "PREVIEW-NODE-001"


def test_unreachable_node_is_rejected():
    dsl = _dsl(
        [_node("start", "start"), _node("answer", "end", {"resultMapping": {"a": "b"}})],
        [{"from": "start", "to": "answer"}],
    )
    dsl["nodes"].append(_node("orphan", "transform"))
    with pytest.raises(PermanentNodeError) as exc:
        plan_preview(dsl, "orphan")
    assert exc.value.code == "PREVIEW-GRAPH-001"


def test_loop_upstream_is_rejected_with_actionable_message():
    dsl = _dsl(
        [
            _node("start", "start"),
            _node(
                "each",
                "loop",
                {
                    "items": "{{ vars.question }}",
                    "itemVar": "item",
                    "body": {
                        "nodes": [
                            _node("inner_start", "start"),
                            _node("inner_end", "end", {"resultMapping": {"a": "b"}}),
                        ],
                        "edges": [{"from": "inner_start", "to": "inner_end"}],
                    },
                },
            ),
            _node("answer", "end", {"resultMapping": {"a": "b"}}),
        ],
        [
            {"from": "start", "to": "each"},
            {"from": "each", "to": "answer"},
        ],
    )

    with pytest.raises(PermanentNodeError) as exc:
        plan_preview(dsl, "each")
    assert exc.value.code == "PREVIEW-STRUCTURE-001"
    assert "循环" in exc.value.message


def test_variables_and_defaults_are_preserved():
    dsl = dict(LINEAR)
    dsl["defaults"] = {"timeoutMs": 5000, "maxParallelism": 2}

    plan = plan_preview(dsl, "draft")
    assert plan.dsl["variables"] == VARIABLES
    assert plan.dsl["defaults"]["timeoutMs"] == 5000
    assert plan.dsl["variables"]["question"]["required"] is True
