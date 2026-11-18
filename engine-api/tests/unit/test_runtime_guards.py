"""运行期约束的回归护栏：截止时间、模板放大、生产守护信号。

这三处都属于「静默劣化」型缺陷——没有异常、没有报错，只是慢慢偏离设计语义，
因此各留一条显式断言，避免后续重构悄悄改回去。
"""

from __future__ import annotations

import time
import uuid
from datetime import UTC, datetime, timedelta

import pytest

from app.engine.scheduler import _deadline_from
from app.engine.state import ExecutionState
from app.errors import ExpressionError
from app.expression.template import MAX_RENDERED_CHARS, render_template
from app.nodes.base import Policy


def _state_with_age(seconds: float) -> ExecutionState:
    state = ExecutionState(execution_id=uuid.uuid4(), seed="seed")
    state.created_at = datetime.now(UTC) - timedelta(seconds=seconds)
    return state


def test_deadline_counts_from_execution_creation() -> None:
    """崩溃恢复不重置总超时：已跑满预算的执行应立即到点，而不是重新计时。"""
    state = _state_with_age(30)
    deadline = _deadline_from(state, Policy(deadline_ms=10_000))

    assert deadline is not None
    assert deadline <= time.monotonic()


def test_deadline_keeps_remaining_budget_after_restart() -> None:
    state = _state_with_age(4)
    deadline = _deadline_from(state, Policy(deadline_ms=10_000))

    assert deadline is not None
    remaining = deadline - time.monotonic()
    assert 5.0 <= remaining <= 6.5


def test_deadline_absent_when_policy_has_none() -> None:
    assert _deadline_from(_state_with_age(1), Policy(deadline_ms=None)) is None


def test_template_output_is_capped() -> None:
    oversized = "x" * (MAX_RENDERED_CHARS + 1)
    with pytest.raises(ExpressionError, match="too large"):
        render_template("{{ text }}", {"text": oversized})


def test_template_allows_output_at_limit() -> None:
    at_limit = "x" * MAX_RENDERED_CHARS
    assert len(render_template("{{ text }}", {"text": at_limit})) == MAX_RENDERED_CHARS


def test_production_guard_accepts_multiple_signals(monkeypatch) -> None:
    from app.config import production_enabled

    for env in ("AF_PRODUCTION", "AF_ENV", "SPRING_PROFILES_ACTIVE"):
        monkeypatch.delenv(env, raising=False)

    monkeypatch.setenv("AF_PRODUCTION", "true")
    assert production_enabled()

    monkeypatch.delenv("AF_PRODUCTION")
    monkeypatch.setenv("AF_ENV", "staging")
    assert production_enabled()

    monkeypatch.delenv("AF_ENV")
    monkeypatch.setenv("SPRING_PROFILES_ACTIVE", "prod,metrics")
    assert production_enabled()


def test_explicit_dev_env_wins_over_other_signals(monkeypatch) -> None:
    """本地用 prod 档位调试其余配置时，AF_ENV=dev 应能压住守护。"""
    from app.config import production_enabled

    monkeypatch.setenv("AF_PRODUCTION", "true")
    monkeypatch.setenv("AF_ENV", "dev")
    assert not production_enabled()
