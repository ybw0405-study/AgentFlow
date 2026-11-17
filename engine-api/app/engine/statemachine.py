"""状态机守卫（§8.1）。非法转移：拒绝并由调用方写审计（WF-STATE-001）。"""

from __future__ import annotations

from ..errors import StateTransitionError

EXECUTION_TRANSITIONS: dict[str, frozenset[str]] = {
    "PENDING": frozenset({"RUNNING", "TIMED_OUT", "CANCELLED"}),
    "RUNNING": frozenset({"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"}),
    "SUCCEEDED": frozenset(),
    "FAILED": frozenset(),
    "TIMED_OUT": frozenset(),
    "CANCELLED": frozenset(),
}

NODE_TRANSITIONS: dict[str, frozenset[str]] = {
    "PENDING": frozenset({"SCHEDULED", "SKIPPED", "CANCELLED"}),
    "SCHEDULED": frozenset({"RUNNING", "CANCELLED"}),
    "RUNNING": frozenset({"SUCCEEDED", "FAILED", "SUSPENDED", "CANCELLED"}),
    # FAILED → RUNNING 只允许经 NodeRetryScheduled 开新 attempt（由 NodeRunner 保证）
    "FAILED": frozenset({"RUNNING"}),
    "SUSPENDED": frozenset({"RUNNING", "CANCELLED"}),
    "SUCCEEDED": frozenset(),
    "SKIPPED": frozenset(),
    "CANCELLED": frozenset(),
}

EXECUTION_TERMINAL = frozenset({"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"})
NODE_TERMINAL = frozenset({"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"})


def ensure_execution_transition(frm: str, to: str) -> None:
    if to not in EXECUTION_TRANSITIONS.get(frm, frozenset()):
        raise StateTransitionError(f"illegal execution transition {frm} -> {to}")


def ensure_node_transition(frm: str, to: str) -> None:
    if to not in NODE_TRANSITIONS.get(frm, frozenset()):
        raise StateTransitionError(f"illegal node transition {frm} -> {to}")
