"""human_gate：挂起 → 裁决 → 续跑（P3）。

覆盖：挂起不落终态、approve/reject 分支、超时按 onTimeout 自动裁决、
重复裁决幂等、非 gate 节点拒绝、子图内禁止 gate、续跑保留 transform 变量。
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta

from app.dsl.validate import validate_dsl
from app.engine.requests import ExecuteRequest

from .conftest import make_execute_request, make_stack
from .test_scheduler import wait_terminal


def _gate_dsl(
    *, timeout_ms: int = 60_000, on_timeout: str = "REJECT", default_branch: str | None = None
) -> dict:
    config: dict = {
        "approvers": {"type": "ROLE", "role": "ADMIN"},
        "promptTemplate": "请确认：{{ vars.mid }}",
        "timeoutMs": timeout_ms,
        "onTimeout": {"action": on_timeout},
    }
    if default_branch:
        config["onTimeout"]["defaultBranch"] = default_branch
    return {
        "dslVersion": "1.0",
        "variables": {"question": {"type": "string", "required": True}},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "prep",
                "type": "transform",
                "config": {
                    "mappings": [{"target": "vars.mid", "template": "{{ vars.question }}-mid"}]
                },
            },
            {"key": "gate", "type": "human_gate", "config": config},
            {
                "key": "ok",
                "type": "transform",
                "config": {
                    "mappings": [{"target": "vars.answer", "template": "approved:{{ vars.mid }}"}]
                },
            },
            {
                "key": "no",
                "type": "transform",
                "config": {
                    "mappings": [{"target": "vars.answer", "template": "rejected:{{ vars.mid }}"}]
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ vars.answer }}"}},
            },
        ],
        "edges": [
            {"from": "start", "to": "prep"},
            {"from": "prep", "to": "gate"},
            {"from": "gate", "to": "ok", "branch": "approved"},
            {"from": "gate", "to": "no", "branch": "rejected"},
            {"from": "ok", "to": "end"},
            {"from": "no", "to": "end"},
        ],
    }


async def _wait_gate(store, execution_id, timeout_s: float = 10.0) -> list:
    for _ in range(int(timeout_s / 0.05)):
        events = await store.read_all(execution_id)
        if any(str(e.event_type) == "GateSuspended" for e in events):
            return events
        await asyncio.sleep(0.05)
    raise AssertionError("gate did not suspend in time")


def _terminal(events) -> list:
    return [
        e
        for e in events
        if str(e.event_type).startswith(
            ("ExecutionCompleted", "ExecutionFailed", "ExecutionTimedOut", "ExecutionCancelled")
        )
    ]


async def _submit(dispatcher, dsl: dict):
    """返回 (request, executionId)；request 复用可模拟恢复扫描的重复 submit。"""
    req = ExecuteRequest.model_validate(
        make_execute_request(dsl, initialVariables={"question": "q"})
    )
    await dispatcher.submit(req)
    return req, req.execution_id


async def test_gate_suspends_without_terminal(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    events = await _wait_gate(mem_store, eid)

    assert not _terminal(events), "挂起不得落执行级终态事件"
    suspended = [e for e in events if str(e.event_type) == "GateSuspended"]
    assert len(suspended) == 1
    payload = suspended[0].payload
    assert payload["prompt"].startswith("请确认：q-mid")
    assert payload["approversRule"]["type"] == "ROLE"
    assert payload["onTimeout"] == "REJECT"
    assert datetime.fromisoformat(payload["timeoutAt"]) > datetime.now(UTC)
    assert [e.node_key for e in events if str(e.event_type) == "NodeSuspended"] == ["gate"]


async def test_approve_resumes_approved_branch(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)

    result = await dispatcher.resolve_gate(eid, "gate", "APPROVED", "u-admin", "同意")
    assert result["ok"] and result["branch"] == "approved"
    assert await wait_terminal(mem_store, eid) == "SUCCEEDED"

    events = await mem_store.read_all(eid)
    resolved = [e for e in events if str(e.event_type) == "GateResolved"]
    assert resolved[0].payload == {
        "decision": "APPROVED",
        "resolvedBy": "u-admin",
        "comment": "同意",
    }
    gate_done = next(
        e for e in events if str(e.event_type) == "NodeCompleted" and e.node_key == "gate"
    )
    assert gate_done.payload["output"]["branch"] == "approved"
    completed = [e for e in _terminal(events) if str(e.event_type) == "ExecutionCompleted"][-1]
    # 分支正确 + 续跑保留了 prep 写入的 vars.mid
    assert completed.payload["result"] == {"answer": "approved:q-mid"}


async def test_reject_resumes_rejected_branch(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)

    result = await dispatcher.resolve_gate(eid, "gate", "REJECTED", "u-admin", None)
    assert result["branch"] == "rejected"
    assert await wait_terminal(mem_store, eid) == "SUCCEEDED"
    events = await mem_store.read_all(eid)
    completed = [e for e in _terminal(events) if str(e.event_type) == "ExecutionCompleted"][-1]
    assert completed.payload["result"] == {"answer": "rejected:q-mid"}
    # 未命中分支及其下游 SKIPPED，而不是消失
    assert {e.node_key for e in events if str(e.event_type) == "NodeSkipped"} == {"ok"}


async def test_timeout_auto_resolves_rejected(settings, fake_redis, mem_store, monkeypatch):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)

    # 只需验证「到期即裁决」：把 deadline 解析钳到过去（等价于等满 timeoutMs）
    monkeypatch.setattr(
        "app.engine.dispatcher._parse_iso",
        lambda _v: datetime.now(UTC) - timedelta(seconds=1),
    )
    assert await dispatcher.sweep_gates() == 1
    assert await wait_terminal(mem_store, eid) == "SUCCEEDED"
    events = await mem_store.read_all(eid)
    resolved = next(e for e in events if str(e.event_type) == "GateResolved")
    assert resolved.payload["decision"] == "TIMEOUT"
    assert resolved.payload["resolvedBy"] == "system:timeout"


async def test_timeout_default_branch_uses_configured_branch(
    settings, fake_redis, mem_store, monkeypatch
):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    dsl = _gate_dsl(on_timeout="DEFAULT_BRANCH", default_branch="approved")
    _, eid = await _submit(dispatcher, dsl)
    await _wait_gate(mem_store, eid)

    monkeypatch.setattr(
        "app.engine.dispatcher._parse_iso",
        lambda _v: datetime.now(UTC) - timedelta(seconds=1),
    )
    await dispatcher.sweep_gates()
    assert await wait_terminal(mem_store, eid) == "SUCCEEDED"
    events = await mem_store.read_all(eid)
    completed = [e for e in _terminal(events) if str(e.event_type) == "ExecutionCompleted"][-1]
    assert completed.payload["result"] == {"answer": "approved:q-mid"}


async def test_resolve_is_idempotent(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)

    first = await dispatcher.resolve_gate(eid, "gate", "APPROVED", "u-admin", None)
    assert first["status"] == "RESOLVED"
    await wait_terminal(mem_store, eid)
    again = await dispatcher.resolve_gate(eid, "gate", "REJECTED", "u-admin", None)
    assert again["status"] in ("ALREADY_RESOLVED", "ALREADY_TERMINAL")
    events = await mem_store.read_all(eid)
    assert len([e for e in events if str(e.event_type) == "GateResolved"]) == 1


async def test_resolve_rejects_unknown_gate(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    _, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)

    result = await dispatcher.resolve_gate(eid, "prep", "APPROVED", "u-admin", None)
    assert result == {"ok": False, "status": "NOT_SUSPENDED"}
    bad = await dispatcher.resolve_gate(eid, "gate", "MAYBE", "u-admin", None)
    assert bad == {"ok": False, "status": "INVALID_DECISION"}


async def test_resubmit_of_suspended_execution_does_not_reexecute(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req, eid = await _submit(dispatcher, _gate_dsl())
    await _wait_gate(mem_store, eid)
    before = len(await mem_store.read_all(eid))

    # 恢复扫描会重新 submit 同一个 executionId：必须短路，不得重复落 GateSuspended
    again = await dispatcher.submit(req)
    assert again["suspended"] is True and again["gateNodeKey"] == "gate"
    await asyncio.sleep(0.2)
    assert len(await mem_store.read_all(eid)) == before


def test_human_gate_branch_must_be_approved_or_rejected(settings):
    dsl = _gate_dsl()
    dsl["edges"][2]["branch"] = "yes"
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any(e.code == "DSL-BRANCH-001" for e in result.errors)


def test_human_gate_inside_loop_body_is_rejected(settings):
    dsl = {
        "dslVersion": "1.0",
        "variables": {"items": {"type": "array", "required": True}},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 2},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "loop",
                "type": "loop",
                "config": {
                    "itemsExpression": "vars.items",
                    "body": {
                        "nodes": [
                            {
                                "key": "gate",
                                "type": "human_gate",
                                "config": {
                                    "approvers": {"type": "ROLE", "role": "ADMIN"},
                                    "promptTemplate": "approve?",
                                },
                            },
                            {
                                "key": "end",
                                "type": "end",
                                "config": {"resultMapping": {"v": "{{ vars.item }}"}},
                            },
                        ],
                        "edges": [{"from": "gate", "to": "end"}],
                    },
                },
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"done": "1"}}},
        ],
        "edges": [{"from": "start", "to": "loop"}, {"from": "loop", "to": "end"}],
    }
    result = validate_dsl(dsl, settings)
    assert not result.ok
    assert any("top-level graph" in e.message for e in result.errors)
