"""预算账本（D11）：预占/结算/耗尽；US-05 预算硬停止。"""

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


async def test_reconcile_refunds_leaked_reservation(fake_redis):
    """崩溃在 CommandCompleted 与 settle 之间：预占多算，对账按事件流回收。"""
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=50)
    assert await ledger.reconcile(eid, used=20) == 30
    assert await ledger.used(eid) == 20


async def test_reconcile_is_idempotent(fake_redis):
    ledger = BudgetLedger(fake_redis)
    eid = uuid.uuid4()
    await ledger.reserve(eid, budget=100, requested=50)
    await ledger.reconcile(eid, used=50)
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
    await ledger.reconcile(eid, used=25)  # 事件流显示已用更多：以事件流为准
    assert await ledger.used(eid) == 25
