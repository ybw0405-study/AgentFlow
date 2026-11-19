"""DSL 校验：US-03 环检测 422 + 结构规则族。"""

from __future__ import annotations

import copy

from app.dsl.validate import (
    DSL_BRANCH,
    DSL_CYCLE,
    DSL_ORPHAN,
    DSL_REF,
    DSL_VALIDATION,
    validate_dsl,
)


def _codes(result):
    return {e.code for e in result.errors}


def test_valid_demo_dsl(settings, demo_dsl):
    result = validate_dsl(demo_dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]
    assert result.checksum and len(result.checksum) == 64
    assert result.normalized_dsl is not None


def test_checksum_is_stable(settings, demo_dsl):
    a = validate_dsl(demo_dsl, settings)
    b = validate_dsl(copy.deepcopy(demo_dsl), settings)
    assert a.checksum == b.checksum


def test_cycle_detected(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["edges"].append({"from": "final", "to": "draft"})  # draft→final→draft
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert DSL_CYCLE in _codes(result)
    cycle_err = next(e for e in result.errors if e.code == DSL_CYCLE)
    assert "draft" in cycle_err.message and "final" in cycle_err.message


def test_duplicate_node_key(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].append(copy.deepcopy(dsl["nodes"][1]))  # 再来一个 draft
    result = validate_dsl(dsl, settings)
    assert DSL_VALIDATION in _codes(result)
    assert any("duplicate nodeKey" in e.message for e in result.errors)


def test_unknown_edge_endpoint(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["edges"].append({"from": "draft", "to": "ghost"})
    result = validate_dsl(dsl, settings)
    assert DSL_REF in _codes(result)


def test_undefined_variable_reference(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"][1]["config"]["promptTemplate"] = "{{ vars.ghost_var }}"
    result = validate_dsl(dsl, settings)
    assert DSL_REF in _codes(result)
    assert any("vars.ghost_var" in e.message for e in result.errors)


def test_non_ancestor_node_reference(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    # draft 引用了非上游的 final（final 依赖 draft）
    dsl["nodes"][1]["config"]["promptTemplate"] = "{{ nodes.final.output.text }}"
    result = validate_dsl(dsl, settings)
    assert DSL_REF in _codes(result)


def test_orphan_node(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].append({"key": "island", "type": "end", "config": {"resultMapping": {"x": "1"}}})
    result = validate_dsl(dsl, settings)
    assert DSL_ORPHAN in _codes(result)


def test_phase_gate_rejects_unimplemented_node(settings, demo_dsl):
    """阶段门只放行已实现执行器的节点：retrieval 尚未接入真实检索，仍应被拒绝。"""
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].append(
        {
            "key": "lookup",
            "type": "retrieval",
            "config": {"knowledgeBaseIds": ["kb-1"], "queryTemplate": "{{ vars.question }}"},
        }
    )
    dsl["edges"].append({"from": "final", "to": "gate"})
    result = validate_dsl(dsl, settings)
    assert DSL_VALIDATION in _codes(result)
    assert any("not enabled" in e.message for e in result.errors)


def test_branch_edge_from_non_branch_node(settings, demo_dsl):
    dsl = copy.deepcopy(demo_dsl)
    dsl["edges"][2]["branch"] = "yes"  # draft(llm) → final 带 branch
    result = validate_dsl(dsl, settings)
    assert DSL_BRANCH in _codes(result)


def test_schema_level_error(settings):
    result = validate_dsl({"dslVersion": "2.0", "nodes": []}, settings)
    assert not result.ok
    assert DSL_VALIDATION in _codes(result)


# ---------- 子图作用域（loop / parallel） ----------


def _loop_dsl(*, body_nodes, body_edges=None, items="vars.tags"):
    """最小 loop 工作流：start → loop_1 → end。"""
    return {
        "dslVersion": "1.0",
        "variables": {"tags": {"type": "array", "required": True}},
        "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "loop_1",
                "type": "loop",
                "config": {
                    "itemsExpression": items,
                    "itemVar": "item",
                    "indexVar": "index",
                    "maxIterations": 10,
                    "body": {"nodes": body_nodes, "edges": body_edges or []},
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"n": "{{ nodes.loop_1.output.count }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "loop_1"}, {"from": "loop_1", "to": "end"}],
    }


def test_loop_body_can_reference_iteration_variable(settings):
    """子图内的 vars.item / vars.index 属于该 loop 的局部作用域，不应判未声明。"""
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"pair": "{{ vars.index }}-{{ vars.item }}"}},
            }
        ]
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_loop_body_unknown_variable_still_rejected(settings):
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ vars.nope }}"}},
            }
        ]
    )
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("vars.nope" in e.message for e in result.errors)


def test_loop_body_can_reference_sibling_upstream_node(settings):
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_mid",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.upper", "template": "{{ vars.item }}"}]},
            },
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ nodes.body_mid.output.upper }}"}},
            },
        ],
        body_edges=[{"from": "body_mid", "to": "body_end"}],
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_loop_body_rejects_downstream_node_reference(settings):
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_up",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.x", "template": "{{ vars.item }}"}]},
            },
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ nodes.body_up.output.x }}"}},
            },
        ]
    )  # 没有 body_up → body_end 的边：引用非上游
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("body_up" in e.message for e in result.errors)


def test_loop_body_can_reference_outer_upstream_node(settings):
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ nodes.loop_1.output.count }}"}},
            }
        ]
    )
    # 引用 loop 自身不算上游，必须报错
    result = validate_dsl(dsl, settings)
    assert not result.ok


def test_nested_branch_edge_rules_apply_inside_loop(settings):
    """子图内的 branch 边也要校验：transform 不是分支节点。"""
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_mid",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.x", "template": "{{ vars.item }}"}]},
            },
            {"key": "body_end", "type": "end", "config": {"resultMapping": {"v": "1"}}},
        ],
        body_edges=[{"from": "body_mid", "to": "body_end", "branch": "yes"}],
    )
    result = validate_dsl(dsl, settings)
    assert DSL_BRANCH in _codes(result)


def test_transform_target_is_definition_not_reference(settings, demo_dsl):
    """把结果写进一个新变量不该要求先在 variables 里声明。"""
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].insert(
        1,
        {
            "key": "prep",
            "type": "transform",
            "config": {"mappings": [{"target": "vars.tmp_sum", "template": "{{ vars.question }}"}]},
        },
    )
    dsl["edges"] = (
        [{"from": "start", "to": "prep"}]
        + [e for e in dsl["edges"] if not (e["from"] == "start" and e["to"] == "draft")]
        + [{"from": "prep", "to": "draft"}]
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_loop_collect_into_is_definition_not_reference(settings):
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ vars.item }}"}},
            }
        ]
    )
    dsl["nodes"][1]["config"]["collect"] = {"into": "collected", "item": "v"}
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_parallel_branch_scoped_variables(settings):
    """parallel 各分支共享外层变量；分支内引用未声明变量仍然报错。"""
    branch = {
        "nodes": [
            {"key": "b_end", "type": "end", "config": {"resultMapping": {"v": "{{ vars.tags }}"}}}
        ],
        "edges": [],
    }
    dsl = {
        "dslVersion": "1.0",
        "variables": {"tags": {"type": "array", "required": True}},
        "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "fan",
                "type": "parallel",
                "config": {"branches": {"a": branch, "b": branch}, "join": "all"},
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"n": "{{ nodes.fan.output.count }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "fan"}, {"from": "fan", "to": "end"}],
    }
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_written_variable_visible_downstream(settings, demo_dsl):
    """transform 写入的变量在它的下游可直接引用（不必先声明）。"""
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].insert(
        1,
        {
            "key": "prep",
            "type": "transform",
            "config": {"mappings": [{"target": "vars.tmp_sum", "template": "{{ vars.question }}"}]},
        },
    )
    dsl["nodes"][-1]["config"]["resultMapping"]["brief"] = "{{ vars.tmp_sum }}"
    dsl["edges"] = (
        [{"from": "start", "to": "prep"}]
        + [e for e in dsl["edges"] if not (e["from"] == "start" and e["to"] == "draft")]
        + [{"from": "prep", "to": "draft"}]
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_written_variable_not_visible_on_sibling_branch(settings, demo_dsl):
    """写入型变量只对下游可见：兄弟分支引用它仍视为未声明（否则运行期必炸）。"""
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].insert(
        1,
        {
            "key": "prep",
            "type": "transform",
            "config": {"mappings": [{"target": "vars.tmp_sum", "template": "{{ vars.question }}"}]},
        },
    )
    dsl["nodes"][-1]["config"]["resultMapping"]["brief"] = "{{ vars.tmp_sum }}"
    # prep 与 draft 都是 start 的下游，prep 的写入对 draft 不可见
    dsl["edges"] = [{"from": "start", "to": "prep"}, *list(dsl["edges"])]
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert DSL_REF in _codes(result)
    assert any("tmp_sum" in e.message for e in result.errors)
