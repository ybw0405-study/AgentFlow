"""裁决消费端的分类与重试语义。

这条通道的成败不能只看「有没有推进状态机」：HTTP 是快路径，MQ 大多数时候是在
重复劳动（duplicate）或处理迟到的消息（stale）。把这两类算成 failed 会让告警长期
是红的，也会掩盖真正的故障——所以逐类钉住。
"""

from __future__ import annotations

import pytest

from app.messaging import GateDecisionConsumer

_EXECUTION_ID = "3f0a1b2c-9d4e-4f60-8a71-2b3c4d5e6f70"


class FakeDispatcher:
    """按预设脚本返回 status；遇到异常对象就抛出来。"""

    def __init__(self, *statuses: str | Exception) -> None:
        self._statuses = list(statuses) or ["RESOLVED"]
        self.calls: list[tuple[str, str, str, str, str | None]] = []

    async def resolve_gate(self, execution_id, node_key, decision, resolved_by, comment=None):
        self.calls.append((str(execution_id), node_key, decision, resolved_by, comment))
        status = self._statuses.pop(0) if len(self._statuses) > 1 else self._statuses[0]
        if isinstance(status, Exception):
            raise status
        return {"ok": True, "status": status}


def _consumer(dispatcher: FakeDispatcher, **kwargs) -> GateDecisionConsumer:
    return GateDecisionConsumer(
        dispatcher,
        namesrv="127.0.0.1:9876",
        topic="af-gate-decision",
        group="af-engine-gate-consumer",
        busy_retry_delay_s=0,
        **kwargs,
    )


def _payload(**overrides) -> dict:
    payload = {
        "executionId": _EXECUTION_ID,
        "nodeKey": "gate_1",
        "decision": "APPROVED",
        "resolvedBy": "u-1",
        "comment": "ok",
    }
    payload.update(overrides)
    return payload


async def test_resolved_counts_as_applied():
    dispatcher = FakeDispatcher("RESOLVED")
    consumer = _consumer(dispatcher)

    await consumer._handle(_payload())

    assert consumer.status()["applied"] == 1
    assert consumer.status()["failed"] == 0
    assert dispatcher.calls[0][:3] == (_EXECUTION_ID, "gate_1", "APPROVED")


async def test_already_resolved_counts_as_duplicate():
    consumer = _consumer(FakeDispatcher("ALREADY_RESOLVED"))

    await consumer._handle(_payload())

    assert consumer.status()["duplicate"] == 1
    assert consumer.status()["failed"] == 0


async def test_terminal_execution_is_stale_not_failure():
    """执行已被 HTTP 抢先收口：消息迟到，不该记成失败。"""

    consumer = _consumer(FakeDispatcher("ALREADY_TERMINAL"))

    await consumer._handle(_payload())

    stats = consumer.status()
    assert stats["stale"] == 1
    assert stats["failed"] == 0
    assert stats["lastError"] is None


async def test_not_suspended_is_stale():
    consumer = _consumer(FakeDispatcher("NOT_SUSPENDED"))

    await consumer._handle(_payload())

    assert consumer.status()["stale"] == 1
    assert consumer.status()["failed"] == 0


async def test_not_found_is_invalid_not_failure():
    """执行不存在说明消息本身对不上，单独计数，不混进 failed。"""

    consumer = _consumer(FakeDispatcher("NOT_FOUND"))

    await consumer._handle(_payload())

    assert consumer.status()["invalid"] == 1
    assert consumer.status()["failed"] == 0


async def test_busy_is_retried_then_applied():
    """BUSY 通常意味着 HTTP 快路径正持租约：等一拍再试，别丢消息。"""

    dispatcher = FakeDispatcher("BUSY", "RESOLVED")
    consumer = _consumer(dispatcher)

    await consumer._handle(_payload())

    assert len(dispatcher.calls) == 2
    assert consumer.status()["applied"] == 1
    assert consumer.status()["failed"] == 0


async def test_busy_exhausts_retries_and_records_failure():
    dispatcher = FakeDispatcher("BUSY")
    consumer = _consumer(dispatcher, busy_retries=2)

    await consumer._handle(_payload())

    stats = consumer.status()
    assert len(dispatcher.calls) == 3 # 首轮 + 2 次重试
    assert stats["failed"] == 1
    assert "BUSY" in (stats["lastError"] or "")


async def test_dispatcher_exception_propagates_for_dispatch_to_count():
    """_handle 不吞异常：由 _dispatch 统一记 failed，避免同时计两次。"""

    consumer = _consumer(FakeDispatcher(RuntimeError("boom")))

    with pytest.raises(RuntimeError):
        await consumer._handle(_payload())


def test_decode_normalises_decision_and_rejects_bad_payloads():
    consumer = _consumer(FakeDispatcher())

    assert (
        consumer._decode({"body": b'{"executionId":"e","nodeKey":"n","decision":"approved"}'})[
            "decision"
        ]
        == "APPROVED"
    )
    assert consumer.status()["invalid"] == 0

    for bad in (
        {"body": b"not-json"},
        {"body": b'{"nodeKey":"n","decision":"APPROVED"}'},
        {"body": b'{"executionId":"e","nodeKey":"n","decision":"MAYBE"}'},
    ):
        assert consumer._decode(bad) is None

    assert consumer.status()["invalid"] == 3
    assert consumer.status()["consumed"] == 0
