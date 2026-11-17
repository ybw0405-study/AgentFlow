"""checkpoint 快照与聚合状态的隔离（回归）。

历史缺陷：checkpoint_payload 直接引用 state.variables，事件折叠时
ExecutionState.apply 对 CHECKPOINT_CREATED 执行 clear() + update(快照)，
而快照正是被 clear 的那个字典本身 → 变量在写完 checkpoint 的瞬间被抹成空。
表现：长链路（事件数跨过 checkpoint 间隔）里，transform 写入的变量凭空消失，
下游 human_gate/llm 渲染直接报 EXPR-EVAL-001。
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from app.engine.executor import checkpoint_payload
from app.engine.state import ExecutionState
from app.events.types import Event, EventType


def _state() -> ExecutionState:
    state = ExecutionState(execution_id=uuid.uuid4(), seed="seed-x")
    state.variables.update({"question": "上线", "mid": "上线-prepared"})
    state.version = 7
    return state


def _checkpoint_event(payload: dict, version: int = 8) -> Event:
    return Event(
        seq=1,
        execution_id=uuid.uuid4(),
        event_type=EventType.CHECKPOINT_CREATED,
        node_key=None,
        payload=payload,
        aggregate_version=version,
        created_at=datetime.now(UTC),
    )


def test_checkpoint_payload_does_not_alias_state_variables():
    state = _state()
    payload = checkpoint_payload(state)
    assert payload["variablesSnapshot"] == {"question": "上线", "mid": "上线-prepared"}
    assert payload["variablesSnapshot"] is not state.variables


def test_applying_own_checkpoint_keeps_variables():
    """写 checkpoint 后立刻折叠该事件：变量必须原样保留。"""
    state = _state()
    state.apply(_checkpoint_event(checkpoint_payload(state)))
    assert state.variables == {"question": "上线", "mid": "上线-prepared"}


def test_checkpoint_restores_previous_snapshot():
    """重放旧 checkpoint 时以快照为准（续跑复原）。"""
    state = _state()
    state.apply(_checkpoint_event({"variablesSnapshot": {"question": "回滚"}}, version=9))
    assert state.variables == {"question": "回滚"}


def test_checkpoint_snapshot_is_deep_copied():
    """嵌套结构也要隔离：后续原地改状态不得篡改已落库快照。"""
    state = _state()
    state.variables["meta"] = {"tags": ["a"]}
    payload = checkpoint_payload(state)
    state.variables["meta"]["tags"].append("b")
    assert payload["variablesSnapshot"]["meta"]["tags"] == ["a"]


def test_empty_snapshot_clears_variables():
    """显式空快照表示「确实没有变量」，不能被当成缺省而跳过。"""
    state = _state()
    state.apply(_checkpoint_event({"variablesSnapshot": {}}, version=10))
    assert state.variables == {}
