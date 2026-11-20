"""离线测试连接器：callId 幂等（恰好一次的实现机制，US-04 副作用一次）。"""

from __future__ import annotations

from tests.support.mocks import FakeToolConnector


async def test_search_returns_canned_results(fake_redis):
    conn = FakeToolConnector(fake_redis, "test.search")
    result = await conn.invoke(
        call_id="e/n/1", operation="search", params={"q": "agent"}, timeout_ms=1000, trace_id="t"
    )
    assert result.ok
    assert len(result.data["results"]) == 3


async def test_same_call_id_deduplicates_side_effect(fake_redis):
    conn = FakeToolConnector(fake_redis, "test.search")
    first = await conn.invoke(
        call_id="e/n/1", operation="search", params={"q": "x"}, timeout_ms=1000, trace_id="t"
    )
    second = await conn.invoke(
        call_id="e/n/1", operation="search", params={"q": "x"}, timeout_ms=1000, trace_id="t"
    )
    assert first.ok and second.ok
    # 副作用计数恰好 1；第二次命中 callId 缓存
    count = await fake_redis.get("af:fakecount:test.search:search")
    assert int(count) == 1
    assert second.data.get("_replayed") is True


async def test_flaky_always_transient(fake_redis):
    import pytest

    from app.errors import TransientNodeError

    conn = FakeToolConnector(fake_redis, "test.flaky")
    with pytest.raises(TransientNodeError):
        await conn.invoke(
            call_id="e/f/1", operation="flaky", params={}, timeout_ms=1000, trace_id="t"
        )


async def test_unknown_operation_is_permanent(fake_redis):
    import pytest

    from app.errors import PermanentNodeError

    conn = FakeToolConnector(fake_redis, "test.search")
    with pytest.raises(PermanentNodeError) as exc:
        await conn.invoke(
            call_id="e/n/9", operation="nope", params={}, timeout_ms=1000, trace_id="t"
        )
    assert exc.value.code == "TOOL-OP-001"
