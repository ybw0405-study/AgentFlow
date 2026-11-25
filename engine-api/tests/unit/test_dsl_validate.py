"""DSL 校验：环检测 422 + 结构规则族。"""

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


def test_phase_gate_rejects_node_outside_allowlist(settings, demo_dsl):
    """阶段门仍按 Settings 白名单工作：retrieval 已实现，若配置里摘掉就应被拒绝。

    默认白名单接受 retrieval 由 test_retrieval.py::test_phase_gate_accepts_retrieval_node 守住，
    这里验证「白名单机制本身」没有随着放开节点类型一起失效。
    """
    dsl = copy.deepcopy(demo_dsl)
    dsl["nodes"].append(
        {
            "key": "lookup",
            "type": "retrieval",
            "config": {"knowledgeBaseIds": ["kb-1"], "queryTemplate": "{{ vars.question }}"},
        }
    )
    dsl["edges"].append({"from": "start", "to": "lookup"})
    gated = settings.model_copy(
        update={"supported_node_types": settings.supported_node_types - {"retrieval"}}
    )
    result = validate_dsl(dsl, gated)
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
                "key": "body_prep",
                "type": "transform",
                "config": {
                    "mappings": [
                        {"target": "vars.pair", "template": "{{ vars.index }}-{{ vars.item }}"}
                    ]
                },
            },
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"pair": "{{ vars.item }}"}},
            },
        ]
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_cycle_inside_loop_body_rejected_at_validation(settings):
    """子图内的环必须在发布期以 DSL-CYCLE-001 拦下，而不是运行期 NODE-INTERNAL。"""
    dsl = _loop_dsl(
        body_nodes=[
            {
                "key": "body_prep",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.pair", "template": "{{ vars.item }}"}]},
            },
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"pair": "{{ vars.pair }}"}},
            },
        ],
        # body_end → body_prep 形成环（body_prep → body_end 由默认边隐含）
        body_edges=[
            {"from": "body_prep", "to": "body_end"},
            {"from": "body_end", "to": "body_prep"},
        ],
    )
    result = validate_dsl(dsl, settings)
    assert not result.ok
    cycle_errors = [e for e in result.errors if e.code == DSL_CYCLE]
    assert cycle_errors, [e.model_dump() for e in result.errors]
    assert any("cycle in subgraph" in e.message for e in cycle_errors)


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
                "key": "body_prep",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.v", "template": "{{ vars.item }}"}]},
            },
            {
                "key": "body_end",
                "type": "end",
                "config": {"resultMapping": {"v": "{{ vars.item }}"}},
            },
        ]
    )
    dsl["nodes"][1]["config"]["collect"] = {"into": "collected", "item": "v"}
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_parallel_branch_scoped_variables(settings):
    """parallel 各分支共享外层变量；分支内引用未声明变量仍然报错。"""
    branch = {
        "nodes": [
            {
                "key": "b_prep",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.tags", "template": "{{ vars.tags }}"}]},
            },
            {"key": "b_end", "type": "end", "config": {"resultMapping": {"v": "{{ vars.tags }}"}}},
        ],
        "edges": [{"from": "b_prep", "to": "b_end"}],
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


def _gate_dsl(approvers: dict) -> dict:
    """start → human_gate →(approved) end / (rejected) end 的最小审批工作流。"""
    return {
        "dslVersion": "1.0",
        "variables": {},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "approve",
                "type": "human_gate",
                "config": {
                    "approvers": approvers,
                    "promptTemplate": "请确认",
                    "timeoutMs": 86_400_000,
                    "onTimeout": {"action": "REJECT"},
                },
            },
            {"key": "ok_end", "type": "end", "config": {"resultMapping": {"ok": "yes"}}},
            {"key": "no_end", "type": "end", "config": {"resultMapping": {"ok": "no"}}},
        ],
        "edges": [
            {"from": "start", "to": "approve"},
            {"from": "approve", "to": "ok_end", "branch": "approved"},
            {"from": "approve", "to": "no_end", "branch": "rejected"},
        ],
    }


def test_expression_approver_accepts_supported_subset(settings):
    """发布期放行合法表达式：属性、比较、in、and/or/not、列表字面量。"""
    dsl = _gate_dsl(
        {
            "type": "EXPRESSION",
            "expression": 'user.rank >= 2 and user.id in ["u-1", "u-2"] or user.platformAdmin',
        }
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_expression_approver_rejects_unknown_attribute(settings):
    """写错属性名必须发布期挡住：运行期只会「解析出空审批人」把审批卡死。"""
    dsl = _gate_dsl({"type": "EXPRESSION", "expression": "user.level >= 2"})
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert DSL_VALIDATION in _codes(result)
    assert any("user.level" in e.message for e in result.errors)


def test_expression_approver_rejects_python_only_syntax(settings):
    """函数调用 / 下标 / f-string 都不在子集内（Java 侧无法求值）。"""
    for expr in ("len(user.id) > 3", 'user.id.startswith("u")', "user.id[0] == 'u'"):
        dsl = _gate_dsl({"type": "EXPRESSION", "expression": expr})
        result = validate_dsl(dsl, settings)
        assert not result.ok, expr


def test_expression_approver_requires_expression(settings):
    dsl = _gate_dsl({"type": "EXPRESSION"})
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("expression is required" in e.message for e in result.errors)


def test_expression_approver_syntax_error(settings):
    dsl = _gate_dsl({"type": "EXPRESSION", "expression": "user.rank >>"})
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("not parseable" in e.message for e in result.errors)


def test_expression_approver_accepts_json_style_literals(settings):
    """`true` / `false` / `null` 是画布与 Java 侧都认的写法，发布期不能反而不认。

    Python 的 ast 把它们解析成 Name（Python 字面量是 True/False/None），早期实现
    直接拒掉，结果就是「画布放行、Java 能求值、发布期 422」。
    """
    for expr in (
        "user.platformAdmin == true",
        "user.platformAdmin == TRUE",
        "user.rank > 1 and not false",
        "user.role != null",
        "user.role != none",
        'user.id in ["u-1"] and user.platformAdmin == true',
    ):
        dsl = _gate_dsl({"type": "EXPRESSION", "expression": expr})
        result = validate_dsl(dsl, settings)
        assert result.ok, (expr, [e.message for e in result.errors])


def test_expression_approver_rejects_bare_identifier(settings):
    """`admin == true` 这类漏写 user. 的写法要挡住，别让它变成「永远为空」的审批人。"""
    dsl = _gate_dsl({"type": "EXPRESSION", "expression": "admin == true"})
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("unknown identifier" in e.message for e in result.errors)


def _parallel_dsl(branch_nodes: list[dict]) -> dict:
    """start → parallel → end；branches 内容由入参决定（用于验子图规模约束）。"""
    return {
        "dslVersion": "1.0",
        "variables": {},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "fan",
                "type": "parallel",
                "config": {
                    "join": "all",
                    "branches": {
                        "a": {"nodes": branch_nodes, "edges": []},
                        "b": {"nodes": branch_nodes, "edges": []},
                    },
                },
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"ok": "done"}}},
        ],
        "edges": [{"from": "start", "to": "fan"}, {"from": "fan", "to": "end"}],
    }


def test_single_node_subgraph_is_rejected_at_publish(settings):
    """单节点分支发布期就该拦：引擎运行期按 WorkflowDsl 解析子图，跑起来必炸。"""
    dsl = _parallel_dsl(
        [
            {
                "key": "only",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.a", "template": "a"}]},
            }
        ]
    )
    result = validate_dsl(dsl, settings)
    assert not result.ok
    # 两个分支的错误相同，校验结果按 (node_key, message) 去重，因此只留一条
    assert sum("at least 2 nodes" in e.message for e in result.errors) >= 1, [
        e.message for e in result.errors
    ]


def test_two_node_subgraph_passes(settings):
    dsl = _parallel_dsl(
        [
            {
                "key": "prep",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.a", "template": "a"}]},
            },
            {
                "key": "tail",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.a", "template": "done"}]},
            },
        ]
    )
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.message for e in result.errors]


def test_role_approver_untouched_by_expression_rules(settings):
    dsl = _gate_dsl({"type": "ROLE", "role": "ADMIN"})
    result = validate_dsl(dsl, settings)
    assert result.ok, [e.model_dump() for e in result.errors]


# ---------- 子图嵌套深度（前端即时校验 / 画布下钻 / 发布校验同一口径） ----------


def _nested_loop_dsl(levels: int, *, leaf_template: str = "ok") -> dict:
    """构造 levels 层嵌套 loop：顶层 loop_1，其 body 里再嵌 loop_2 … loop_levels。

    每层 body 都配一个 end 收口节点满足「至少 2 节点」，config 不含未声明引用，
    这样唯一的失败原因只可能是子图深度本身。第 4 层允许，第 5 层必须被拒。
    """

    def subgraph(level: int) -> dict:
        if level >= levels:
            return {
                "nodes": [
                    {
                        "key": f"leaf_{level}_prep",
                        "type": "transform",
                        "config": {"mappings": [{"target": "vars.tmp", "template": leaf_template}]},
                    },
                    {
                        "key": f"leaf_{level}_end",
                        "type": "end",
                        "config": {"resultMapping": {"v": "1"}},
                    },
                ],
                "edges": [{"from": f"leaf_{level}_prep", "to": f"leaf_{level}_end"}],
            }
        child = f"loop_{level + 1}"
        return {
            "nodes": [
                {
                    "key": child,
                    "type": "loop",
                    "config": {
                        "itemsExpression": "vars.tags",
                        "itemVar": "item",
                        "indexVar": "index",
                        "maxIterations": 10,
                        "body": subgraph(level + 1),
                    },
                },
                {"key": f"tail_{level}", "type": "end", "config": {"resultMapping": {"v": "1"}}},
            ],
            "edges": [{"from": child, "to": f"tail_{level}"}],
        }

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
                    "itemsExpression": "vars.tags",
                    "itemVar": "item",
                    "indexVar": "index",
                    "maxIterations": 10,
                    "body": subgraph(1),
                },
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"n": "1"}}},
        ],
        "edges": [{"from": "start", "to": "loop_1"}, {"from": "loop_1", "to": "end"}],
    }


def test_subgraph_depth_at_limit_passes(settings):
    """恰好 4 层子图允许通过：画布能下钻 4 层，发布校验也必须认。

    数字 4 与 app/dsl/validate.py 的 MAX_SUBGRAPH_DEPTH、前端 dsl/validate.ts 的同名常量
    一致；三处任一改动而没同步，这条测试会先变红。
    """
    result = validate_dsl(_nested_loop_dsl(4), settings)
    assert result.ok, [e.model_dump() for e in result.errors]


def test_subgraph_depth_at_limit_still_checks_references(settings):
    """到达深度上限不等于「跳过校验」：最内层的未声明引用仍要报出来。"""
    result = validate_dsl(_nested_loop_dsl(4, leaf_template="{{ vars.ghost }}"), settings)
    assert not result.ok
    assert any("vars.ghost" in e.message for e in result.errors)


def test_subgraph_depth_beyond_limit_is_rejected(settings):
    """第 5 层子图明确 422，并定位到持有它的节点，而不是静默丢弃该子图。"""
    result = validate_dsl(_nested_loop_dsl(5), settings)
    assert not result.ok
    assert DSL_VALIDATION in _codes(result)
    depth_errors = [e for e in result.errors if "subgraph nesting exceeds" in e.message]
    assert depth_errors, [e.model_dump() for e in result.errors]
    assert all("4 levels" in e.message for e in depth_errors)
    assert any(e.node_key == "loop_5" for e in depth_errors)
