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
