"""预算账本（D11）：预占/结算/耗尽；US-05 预算硬停止；§5.6 崩溃对账。"""

from __future__ import annotations

import uuid

from app.governance.budget import BudgetLedger


async def test_reserve_within_budget(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    assert await ledger.reserve(eid, budget=100, requested=60) == 60
    assert await ledger.used(eid) == 60


async def test_reserve_clamps_to_remaining(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    assert await ledger.reserve(eid, budget=100, requested=60) == 60
    assert await ledger.reserve(eid, budget=100, requested=60) == 40  # 钳到剩余
    assert await ledger.used(eid) == 100


async def test_reserve_exhausted_returns_minus_one(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=100)
    assert await ledger.reserve(eid, budget=100, requested=1) == -1


async def test_settle_refunds_unused(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    grant = await ledger.reserve(eid, budget=100, requested=80)
    await ledger.settle(eid, grant, actual=30)
    assert await ledger.used(eid) == 30
    assert await ledger.reserve(eid, budget=100, requested=100) == 70


async def test_reconcile_refunds_leaked_reservation_on_recovery(fake_redis):
    """崩溃在 CommandCompleted 与 settle 之间：接管时清空在途并按事件流回收。"""
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=50)
    assert await ledger.reconcile(eid, used=20, reset_inflight=True) == 30
    assert await ledger.used(eid) == 20


async def test_reconcile_is_idempotent(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=50)
    await ledger.reconcile(eid, used=50, reset_inflight=True)
    assert await ledger.reconcile(eid, used=50) == 0
    assert await ledger.used(eid) == 50


async def test_reconcile_seeds_missing_key_from_events(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    assert await ledger.reconcile(eid, used=7) == 0
    assert await ledger.used(eid) == 7


async def test_reconcile_never_exceeds_event_total(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=10)
    await ledger.reconcile(eid, used=25, reset_inflight=True)  # 事件流为准
    assert await ledger.used(eid) == 25


async def test_reconcile_skips_while_reservation_is_in_flight(fake_redis):
    """checkpoint 落在 reserve 与 settle 之间：不能拿事件流汇总额覆盖在途预占。

    旧实现会 SET 成 20（抹掉 50 的预占），随后 settle 再回冲 30，键变成 -10，
    下一次 reserve 就把剩余额度算成 110 —— 对账动作自己制造了超发。
    """
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    grant = await ledger.reserve(eid, budget=100, requested=50)
    assert await ledger.reconcile(eid, used=20) == 0
    assert await ledger.used(eid) == 50

    await ledger.settle(eid, grant, actual=20)
    assert await ledger.used(eid) == 20
    assert await ledger.reconcile(eid, used=20) == 0
    assert await ledger.used(eid) == 20
    assert await ledger.reserve(eid, budget=100, requested=100) == 80


async def test_inflight_protects_parallel_reservations(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    first = await ledger.reserve(eid, budget=100, requested=40)
    second = await ledger.reserve(eid, budget=100, requested=40)
    assert await ledger.reconcile(eid, used=5) == 0  # 两笔都还在途
    await ledger.settle(eid, first, actual=10)
    assert await ledger.reconcile(eid, used=5) == 0  # 还有一笔在途
    await ledger.settle(eid, second, actual=10)
    assert await ledger.reconcile(eid, used=20) == 0
    assert await ledger.used(eid) == 20


async def test_reconcile_reset_clears_stale_inflight(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=60)
    assert await ledger.reconcile(eid, used=10) == 0  # 被在途挡住
    assert await ledger.reconcile(eid, used=10, reset_inflight=True) == 50
    assert await ledger.used(eid) == 10


async def test_settle_never_drives_inflight_negative(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    grant = await ledger.reserve(eid, budget=100, requested=30)
    await ledger.settle(eid, grant, actual=30)
    await ledger.settle(eid, grant, actual=30)  # 重复结算不能把计数减成负数
    assert await ledger.reserve(eid, budget=100, requested=50) == 50
    assert await ledger.reconcile(eid, used=0) == 0  # 新预占仍在途


async def test_exists_tracks_ledger_key(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    assert await ledger.exists(eid) is False
    await ledger.reserve(eid, budget=100, requested=10)
    assert await ledger.exists(eid) is True
    await ledger.reconcile(eid, used=3, reset_inflight=True)
    assert await ledger.exists(eid) is True
