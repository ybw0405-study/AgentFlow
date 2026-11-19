"""表达式沙箱（§9 安全约束）。"""

from __future__ import annotations

import pytest

from app.errors import ExpressionError
from app.expression.safeexpr import evaluate
from app.expression.template import render_template


def test_basic_arithmetic_and_functions():
    assert evaluate("1 + 2 * 3", {}) == 7
    assert evaluate("upper('abc')", {}) == "ABC"
    assert evaluate("contains('agentflow', 'flow')", {}) is True
    assert evaluate("len([1, 2, 3])", {}) == 3


def test_names_access():
    names = {"vars": {"score": 0.9}, "nodes": {"a": {"output": {"text": "hi"}}}}
    assert evaluate("vars.score > 0.5", names) is True
    assert evaluate("nodes.a.output.text", names) == "hi"


def test_json_get():
    obj = {"a": {"b": [{"c": 42}]}}
    assert evaluate("json_get(vars.doc, '$.a.b[0].c')", {"vars": {"doc": obj}}) == 42


@pytest.mark.parametrize(
    "evil",
    [
        "__import__('os').system('dir')",
        "().__class__.__bases__[0].__subclasses__()",
        "open('x')",
        "eval('1')",
        "vars.__class__",
    ],
)
def test_escape_attempts_blocked(evil):
    with pytest.raises(ExpressionError) as exc:
        evaluate(evil, {"vars": {}})
    assert exc.value.code == "EXPR-EVAL-001"


def test_template_render_ok():
    out = render_template("问题：{{ vars.q }}", {"vars": {"q": "test"}})
    assert out == "问题：test"


def test_template_undefined_is_error():
    with pytest.raises(ExpressionError):
        render_template("{{ vars.ghost }}", {"vars": {}})


def test_template_sandbox_blocks_internals():
    with pytest.raises(ExpressionError):
        render_template("{{ vars.__class__.__mro__ }}", {"vars": {}})


def test_dict_key_named_like_dict_method():
    """vars.items / vars.keys 必须取到变量值，而不是 dict 自带方法。"""
    names = {"vars": {"items": ["a", "b"], "keys": "k", "values": 3, "get": "g"}}
    assert evaluate("vars.items", names) == ["a", "b"]
    assert evaluate("len(vars.items)", names) == 2
    assert evaluate("vars.keys == 'k'", names) is True
    assert evaluate("vars.values + 1", names) == 4
    assert evaluate("vars.get", names) == "g"


def test_nested_dict_key_named_like_dict_method():
    names = {"nodes": {"n1": {"output": {"items": [{"value": "x"}]}}}}
    assert evaluate("nodes.n1.output.items[0].value", names) == "x"


def test_to_json_and_json_get_accept_scoped_dicts():
    names = {"vars": {"items": [1, 2]}}
    assert evaluate("to_json(vars)", names) == '{"items": [1, 2]}'
    assert evaluate("json_get(vars, '$.items[1]')", names) == 2


def test_template_renders_dict_key_named_like_dict_method():
    """模板里 nodes.x.output.items[0] 也要取到值，而不是 dict.items 方法。"""
    ctx = {
        "vars": {"items": ["a", "b"]},
        "nodes": {"loop_1": {"output": {"items": [{"value": "0:a"}], "count": 1}}},
    }
    assert render_template("{{ vars.items[0] }}", ctx) == "a"
    assert render_template("{{ nodes.loop_1.output.items[0].value }}", ctx) == "0:a"
    assert render_template("{{ nodes.loop_1.output.count }}", ctx) == "1"


def test_template_iterates_scoped_list():
    ctx = {"vars": {"items": ["a", "b"]}}
    assert render_template("{% for i in vars.items %}{{ i }}{% endfor %}", ctx) == "ab"
