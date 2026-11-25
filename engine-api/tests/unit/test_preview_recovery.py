"""单节点试跑不得被崩溃恢复扫描当成业务执行续跑。

历史缺陷：preview 走真实 dispatcher 并落 execution_tracker=OPEN，超时后引擎重启，
启动扫描 / 周期 sweep 会把它接管续跑 —— 真实模型重复调用、重复计费。本文件的护栏
钉住四件事：打标随 spec 持久化、恢复扫描跳过并强制终态、收口幂等、写终态失败时
保留标记（否则下次扫描就认不出它是试跑）。
"""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from httpx import ASGITransport, AsyncClient

from app.engine.requests import ExecuteRequest
from app.events.types import TERMINAL_EVENTS, EventType

from .conftest import make_stack


def _request(*, preview: bool) -> ExecuteRequest:
    return ExecuteRequest(
        execution_id=uuid.uuid4(),
        definition_id="preview:n1",
        version_id="preview",
        tenant_id="preview",
        space_id="preview",
        triggered_by="preview",
        idempotency_key=uuid.uuid4().hex,
        version_snapshot={"dslVersion": "1.0", "variables": {}, "nodes": [], "edges": []},
        trace_id=uuid.uuid4().hex,
        preview=preview,
    )


def test_preview_flag_survives_the_spec_roundtrip() -> None:
    spec = _request(preview=True).canonical_dump()
    assert spec["preview"] is True
    assert ExecuteRequest.model_validate(spec).preview is True
    # 未打标的存量 spec 默认按业务执行处理，恢复行为不回归
    assert _request(preview=False).canonical_dump()["preview"] is False
    assert (
        ExecuteRequest.model_validate({k: v for k, v in spec.items() if k != "preview"}).preview
        is False
    )


@pytest.mark.asyncio
async def test_recovery_scan_forces_terminal_instead_of_resuming_preview(
    settings, fake_redis, mem_store
) -> None:
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _request(preview=True)
    eid = req.execution_id
    await mem_store.put_spec(eid, req.canonical_dump())
    await mem_store.append(eid, EventType.EXECUTION_CREATED, {"definitionId": "preview:n1"})
    assert await mem_store.open_executions() == [eid]

    await dispatcher.recover_on_startup()

    events = await mem_store.read_all(eid)
    assert events[-1].event_type in TERMINAL_EVENTS
    assert events[-1].event_type == EventType.EXECUTION_CANCELLED
    assert not any(e.event_type == EventType.EXECUTION_STARTED for e in events)
    assert await mem_store.get_spec(eid) is None
    assert await mem_store.open_executions() == []


@pytest.mark.asyncio
async def test_non_preview_execution_still_goes_through_normal_recovery(
    settings, fake_redis, mem_store, monkeypatch
) -> None:
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _request(preview=False)
    eid = req.execution_id
    await mem_store.put_spec(eid, req.canonical_dump())
    await mem_store.append(eid, EventType.EXECUTION_CREATED, {"definitionId": "def-1"})

    seen: list[ExecuteRequest] = []

    async def fake_submit(body: ExecuteRequest):
        seen.append(body)
        return {"executionId": str(body.execution_id), "status": "RUNNING", "resumed": False}

    monkeypatch.setattr(dispatcher, "submit", fake_submit)
    await dispatcher.recover_on_startup()

    assert len(seen) == 1
    assert seen[0].preview is False
    assert await mem_store.get_spec(eid) is not None # 业务执行的 spec 不能被清掉


@pytest.mark.asyncio
async def test_finalize_preview_is_idempotent_when_already_terminal(
    settings, fake_redis, mem_store
) -> None:
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    eid = _request(preview=True).execution_id
    await mem_store.put_spec(eid, _request(preview=True).canonical_dump())
    await mem_store.append(eid, EventType.EXECUTION_CREATED, {})
    await mem_store.append(eid, EventType.EXECUTION_COMPLETED, {})

    await dispatcher.finalize_preview(eid)

    assert len(await mem_store.read_all(eid)) == 2 # 不重复追加终态事件
    assert await mem_store.get_spec(eid) is None


@pytest.mark.asyncio
async def test_finalize_keeps_the_preview_marker_when_terminal_write_fails(
    settings, fake_redis, mem_store, monkeypatch
) -> None:
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = _request(preview=True)
    eid = req.execution_id
    await mem_store.put_spec(eid, req.canonical_dump())
    await mem_store.append(eid, EventType.EXECUTION_CREATED, {})

    async def boom(*_args, **_kwargs):
        raise RuntimeError("pg down")

    monkeypatch.setattr(mem_store, "read_all", boom)
    await dispatcher.finalize_preview(eid)

    spec = await mem_store.get_spec(eid)
    assert spec is not None and spec["preview"] is True # 下一轮扫描仍能识别


@pytest.mark.asyncio
async def test_preview_endpoint_marks_the_request_and_always_finalizes(
    settings, mem_store, monkeypatch
) -> None:
    """HTTP 层：打标传给 dispatcher，且成功路径也必须走 finally 收口。"""
    import app.api.internal as internal_mod
    from app.main import create_app

    monkeypatch.setattr(
        internal_mod,
        "plan_preview",
        lambda dsl, key: SimpleNamespace(dsl=dsl, upstream=(), target=key, end_key="__end__"),
    )
    monkeypatch.setattr(
        internal_mod,
        "validate_dsl",
        lambda dsl, settings: SimpleNamespace(ok=True, errors=[]),
    )

    captured: dict[str, object] = {}

    class FakeDispatcher:
        async def submit(self, body: ExecuteRequest):
            captured["preview"] = body.preview
            captured["id"] = body.execution_id
            return {"executionId": str(body.execution_id), "status": "PENDING", "resumed": False}

        async def wait(self, execution_id, timeout_s):
            return True

        async def finalize_preview(self, execution_id):
            captured["finalized"] = execution_id

    app = create_app(settings)
    app.state.settings = settings
    app.state.store = mem_store
    app.state.dispatcher = FakeDispatcher()

    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.post(
            "/internal/nodes/preview",
            json={"dsl": {"dslVersion": "1.0"}, "nodeKey": "n1"},
            headers={"X-Internal-Token": settings.internal_token},
        )

    assert response.status_code == 200, response.text
    assert captured.get("preview") is True
    assert captured.get("finalized") == captured.get("id")
