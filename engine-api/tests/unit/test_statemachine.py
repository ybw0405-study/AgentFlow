"""状态机：全部合法/非法转移（§13.2 覆盖要求）。"""

from __future__ import annotations

import pytest

from app.engine.statemachine import (
    EXECUTION_TRANSITIONS,
    NODE_TRANSITIONS,
    ensure_execution_transition,
    ensure_node_transition,
)
from app.errors import StateTransitionError

EXECUTION_STATES = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"]
NODE_STATES = [
    "PENDING",
    "SCHEDULED",
    "RUNNING",
    "SUCCEEDED",
    "FAILED",
    "SKIPPED",
    "SUSPENDED",
    "CANCELLED",
]


@pytest.mark.parametrize("frm", EXECUTION_STATES)
@pytest.mark.parametrize("to", EXECUTION_STATES)
def test_execution_transitions(frm, to):
    if to in EXECUTION_TRANSITIONS.get(frm, frozenset()):
        ensure_execution_transition(frm, to)
    else:
        with pytest.raises(StateTransitionError) as exc:
            ensure_execution_transition(frm, to)
        assert exc.value.code == "WF-STATE-001"


@pytest.mark.parametrize("frm", NODE_STATES)
@pytest.mark.parametrize("to", NODE_STATES)
def test_node_transitions(frm, to):
    if to in NODE_TRANSITIONS.get(frm, frozenset()):
        ensure_node_transition(frm, to)
    else:
        with pytest.raises(StateTransitionError) as exc:
            ensure_node_transition(frm, to)
        assert exc.value.code == "WF-STATE-001"


def test_terminal_states_absorb_everything():
    for terminal in ("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"):
        for to in EXECUTION_STATES:
            with pytest.raises(StateTransitionError):
                ensure_execution_transition(terminal, to)


def test_failed_node_cannot_restart_without_retry_event():
    # FAILED → RUNNING 合法但只能经 NodeRetryScheduled 新 attempt（由 NodeRunner 保证）；
    # FAILED → SUCCEEDED 之类的跳变必须拒绝。
    with pytest.raises(StateTransitionError):
        ensure_node_transition("FAILED", "SUCCEEDED")
