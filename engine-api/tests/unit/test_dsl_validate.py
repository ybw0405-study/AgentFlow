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
