"""执行租约的原子性：renew / release 只作用于仍属于自己的租约。"""

from __future__ import annotations

import asyncio

import pytest

from app.engine import lease as lease_module
from app.engine.lease import Lease


async def test_renew_extends_ttl_for_owner(fake_redis) -> None:
    lease = Lease(fake_redis, "af:lease:e1", ttl_ms=1000, owner="worker-a")
    assert await lease.acquire() is True

    assert await lease.renew() is True
    ttl = await fake_redis.pttl("af:lease:e1")
    assert 0 < ttl <= 1000


async def test_renew_refuses_when_owner_changed(fake_redis) -> None:
    """租约被新 worker 抢占后，旧持有者续约必须失败，且不得改写新租约。"""
    old = Lease(fake_redis, "af:lease:e2", ttl_ms=1000, owner="worker-a")
    assert await old.acquire() is True
    await fake_redis.set("af:lease:e2", "worker-b", px=1000)

    assert await old.renew() is False
    assert await fake_redis.get("af:lease:e2") == "worker-b"


async def test_release_does_not_delete_others_lease(fake_redis) -> None:
    old = Lease(fake_redis, "af:lease:e3", ttl_ms=1000, owner="worker-a")
    assert await old.acquire() is True
    await fake_redis.set("af:lease:e3", "worker-b", px=1000)

    await old.release()

    assert await fake_redis.get("af:lease:e3") == "worker-b"


async def test_release_deletes_own_lease(fake_redis) -> None:
    lease = Lease(fake_redis, "af:lease:e4", ttl_ms=1000, owner="worker-a")
    assert await lease.acquire() is True

    await lease.release()

    assert await fake_redis.get("af:lease:e4") is None


class _HangingRedis:
    """模拟 Redis TCP 半开：调用既不返回也不抛（故障形态）。"""

    async def get(self, *args, **kwargs):
        await asyncio.Event().wait()

    def pipeline(self):
        return _HangingPipeline()


class _HangingPipeline:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def watch(self, *args, **kwargs):
        await asyncio.Event().wait()


async def test_lease_ops_time_out_on_half_open_redis(monkeypatch):
    """Redis 半开时 renew / held_by_me 必须超时返回，而不是永久阻塞。"""
    monkeypatch.setattr(lease_module, "OP_TIMEOUT_S", 0.05)
    lease = Lease(_HangingRedis(), "af:lease:hang", ttl_ms=1000, owner="worker-a")

    with pytest.raises(TimeoutError):
        await lease.renew()
    with pytest.raises(TimeoutError):
        await lease.held_by_me()
