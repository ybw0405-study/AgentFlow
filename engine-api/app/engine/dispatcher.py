"""派发器：单执行单活跃执行器（租约）+ 崩溃恢复扫描（D4）。

恢复链路：execution_spec 表使引擎自足（不读业务库）；启动扫描 +
周期扫描双保险；租约 TTL 到期后接管；重放不重复执行已完成节点。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import secrets
import uuid
from datetime import UTC, datetime
from typing import Any

import redis.asyncio as aioredis

from ..commands.base import digest_of
from ..config import Settings
from ..dsl.models import WorkflowDsl
from ..events.log import EventLog
from ..events.store import EventStore
from ..events.types import EXECUTION_STATUS_OF_EVENT, TERMINAL_EVENTS, EventType
from ..nodes.base import Policy
from ..nodes.registry import NodeExecutor
from ..observability.metrics import EXECUTION_TOTAL, LEASE_CONTENTION_TOTAL, REPLAY_TOTAL
from .lease import Lease
from .replay import rebuild_state
from .requests import ExecuteRequest
from .scheduler import Scheduler
from .state import ExecutionState

logger = logging.getLogger(__name__)


class Dispatcher:
    def __init__(
        self,
        settings: Settings,
        store: EventStore,
        event_log: EventLog,
        redis: aioredis.Redis,
        executors: dict[str, NodeExecutor],
        budget: Any,
    ) -> None:
        self._settings = settings
        self._store = store
        self._event_log = event_log
        self._redis = redis
        self._budget = budget
        self._worker_id = f"engine-{uuid.uuid4().hex[:12]}"
        self._scheduler = Scheduler(
            settings, event_log, store, redis, executors, budget, self._worker_id
        )
        self._tasks: dict[uuid.UUID, asyncio.Task] = {}
        self._sweep_task: asyncio.Task | None = None
        self._gate_task: asyncio.Task | None = None

    async def submit(self, req: ExecuteRequest) -> dict[str, Any]:
        events = await self._store.read_all(req.execution_id)
        if events and events[-1].event_type in TERMINAL_EVENTS:
            return {
                "executionId": str(req.execution_id),
                "status": str(EXECUTION_STATUS_OF_EVENT[events[-1].event_type]),
                "resumed": False,
                "terminal": True,
            }
        gate = _unresolved_gate(events) if events else None
        if gate is not None:
            # 仍在等审批：续跑的唯一入口是裁决回调，调度器不该被反复唤醒
            return {
                "executionId": str(req.execution_id),
                "status": "SUSPENDED",
                "resumed": False,
                "suspended": True,
                "gateNodeKey": gate[0],
            }
        existing = self._tasks.get(req.execution_id)
        if existing is not None and not existing.done():
            return {"executionId": str(req.execution_id), "status": "RUNNING", "resumed": False}

        # 租约 owner 带上随机后缀：同一个 dispatcher 可能先后处理「挂起释放」与
        # 「裁决续跑」，共用 owner 会让上一轮的 release 删掉下一轮刚拿到的租约。
        lease = Lease(
            self._redis,
            f"af:lease:{req.execution_id}",
            self._settings.lease_ttl_ms,
            f"{self._worker_id}:{uuid.uuid4().hex[:8]}",
        )
        if not await lease.acquire():
            LEASE_CONTENTION_TOTAL.inc()
            return {
                "executionId": str(req.execution_id),
                "status": "RUNNING",
                "resumed": False,
                "note": "lease held by another worker",
            }

        dsl = WorkflowDsl.model_validate(req.version_snapshot)
        if events:
            state = rebuild_state(req.execution_id, events)
            REPLAY_TOTAL.inc()
            resumed = True
            # 预算对账：崩溃窗口里未 settle 的预占按事件流回收（§5.6）
            await self._budget.reconcile(req.execution_id, state.token_used)
        else:
            state = ExecutionState(
                execution_id=req.execution_id, seed=req.execution_seed or secrets.token_hex(16)
            )
            await self._store.put_spec(req.execution_id, req.canonical_dump())
            await self._event_log.append(
                req.execution_id,
                EventType.EXECUTION_CREATED,
                {
                    "definitionId": req.definition_id,
                    "versionId": req.version_id,
                    "tenantId": req.tenant_id,
                    "spaceId": req.space_id,
                    "triggeredBy": req.triggered_by,
                    "idempotencyKey": req.idempotency_key,
                    "policy": req.policy.canonical_dump(),
                    "variablesDigest": _digest(req.initial_variables),
                    "traceId": req.trace_id,
                    "executionSeed": state.seed,
                },
                apply=state.apply,
            )
            resumed = False

        task = asyncio.create_task(self._run(req, dsl, state, lease))
        self._tasks[req.execution_id] = task
        return {"executionId": str(req.execution_id), "status": state.status, "resumed": resumed}

    async def wait(self, execution_id: uuid.UUID, timeout_s: float) -> bool:
        """等一个执行跑到当前这一轮结束（试跑用；返回是否在超时前收口）。

        只等本进程持有的任务：超时后置取消位，调度器在下一个节点边界收口，
        不会把已经发出去的模型调用硬砍断。
        """
        task = self._tasks.get(execution_id)
        if task is None:
            return True
        try:
            await asyncio.wait_for(asyncio.shield(task), timeout=max(timeout_s, 0.1))
            return True
        except TimeoutError:
            await self.cancel(execution_id)
            return False

    async def _run(
        self, req: ExecuteRequest, dsl: WorkflowDsl, state: ExecutionState, lease: Lease
    ) -> None:
        renew_stop = asyncio.Event()
        renew_task = asyncio.create_task(self._renew_loop(lease, renew_stop, state))
        try:
            if state.status == "PENDING":
                # 续跑（崩溃恢复 / 审批回填）必须沿用事件流折叠出的变量，
                # 否则 transform/loop 的中间结果会被初始值覆盖。
                state.variables = dict(req.initial_variables)
            policy = Policy(
                token_budget=req.policy.token_budget,
                max_steps=req.policy.max_steps,
                deadline_ms=req.policy.deadline_ms,
            )
            status = await self._scheduler.run(
                execution_id=req.execution_id,
                dsl=dsl,
                state=state,
                trace_id=req.trace_id,
                policy=policy,
                authorized_connector_ids=frozenset(req.authorized_connector_ids),
                lease=lease,
                space_id=req.space_id,
            )
            if status != "LEASE_LOST":
                EXECUTION_TOTAL.labels(status=status.lower()).inc()
        except Exception:
            # 不吞异常也不落事件：事件库保持 OPEN，恢复扫描会接管（D4）
            logger.exception("execution %s worker crashed", req.execution_id)
        finally:
            renew_stop.set()
            await renew_task
            await lease.release()
            self._tasks.pop(req.execution_id, None)

    async def _renew_loop(self, lease: Lease, stop: asyncio.Event, state: ExecutionState) -> None:
        interval = self._settings.lease_renew_ms / 1000
        while not stop.is_set():
            try:
                await asyncio.wait_for(stop.wait(), timeout=interval)
                break
            except TimeoutError:
                pass
            if not await lease.renew():
                logger.error("lease %s lost; executor stops writing events", lease.key)
                state.lease_lost = True  # 调度器在节点边界检查（§8.5）
                return

    async def cancel(self, execution_id: uuid.UUID) -> dict[str, Any]:
        await self._redis.set(f"af:cancel:{execution_id}", "1", ex=3600)
        task = self._tasks.get(execution_id)
        if task is not None and not task.done():
            return {"ok": True, "status": "CANCELLING"}  # 执行器在节点边界收口
        events = await self._store.read_all(execution_id)
        if not events:
            return {"ok": False, "status": "NOT_FOUND"}
        if events[-1].event_type in TERMINAL_EVENTS:
            return {"ok": True, "status": str(EXECUTION_STATUS_OF_EVENT[events[-1].event_type])}
        # 无活跃执行器（如崩溃后）：取租约后直接落取消事件
        lease = Lease(
            self._redis, f"af:lease:{execution_id}", self._settings.lease_ttl_ms, self._worker_id
        )
        if await lease.acquire():
            try:
                state = rebuild_state(execution_id, events)
                await self._event_log.append(
                    execution_id,
                    EventType.EXECUTION_CANCELLED,
                    {"requestedBy": "control-plane"},
                    apply=state.apply,
                )
            finally:
                await lease.release()
            return {"ok": True, "status": "CANCELLED"}
        return {"ok": True, "status": "CANCELLING"}

    async def recover_on_startup(self) -> None:
        """D4：扫描未达终态的执行并接管续跑。"""
        try:
            open_ids = await self._store.open_executions()
        except Exception:
            logger.exception("recovery scan failed")
            return
        for execution_id in open_ids:
            await self._recover_one(execution_id, attempts=3)

    async def _recover_one(self, execution_id: uuid.UUID, *, attempts: int) -> None:
        spec = await self._store.get_spec(execution_id)
        if spec is None:
            logger.error("open execution %s has no spec; skipping", execution_id)
            return
        for i in range(attempts):
            result = await self.submit(ExecuteRequest.model_validate(spec))
            if "note" not in result:
                logger.info(
                    "recovered execution %s (resumed=%s)", execution_id, result.get("resumed")
                )
                return
            # 租约可能尚未过期：等 TTL 后重试
            await asyncio.sleep(self._settings.lease_ttl_ms / 1000 + 1)
            if i == attempts - 1:
                logger.warning(
                    "execution %s still leased elsewhere; next sweep will retry", execution_id
                )

    async def resolve_gate(
        self,
        execution_id: uuid.UUID,
        node_key: str,
        decision: str,
        resolved_by: str,
        comment: str | None = None,
    ) -> dict[str, Any]:
        """裁决 human_gate 并续跑执行。

        幂等语义：节点已终态（重复点击/outbox 重投）返回 ALREADY_RESOLVED；
        仍在等别的裁决返回 NOT_SUSPENDED；租约被占返回 BUSY（调用方可重试）。
        """
        decision = (decision or "").upper()
        if decision not in ("APPROVED", "REJECTED", "TIMEOUT"):
            return {"ok": False, "status": "INVALID_DECISION"}
        events = await self._store.read_all(execution_id)
        if not events:
            return {"ok": False, "status": "NOT_FOUND"}
        state = rebuild_state(execution_id, events)
        if state.status in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"):
            return {"ok": True, "status": "ALREADY_TERMINAL", "executionStatus": state.status}
        gate = _gate_for(events, node_key)
        if gate is None:
            return {"ok": False, "status": "NOT_SUSPENDED"}
        if gate["resolved"]:
            return {
                "ok": True,
                "status": "ALREADY_RESOLVED",
                "branch": gate["payload"].get("branch"),
            }

        lease = Lease(
            self._redis,
            f"af:lease:{execution_id}",
            self._settings.lease_ttl_ms,
            f"{self._worker_id}:gate:{uuid.uuid4().hex[:8]}",
        )
        if not await self._acquire_with_retry(lease):
            return {"ok": False, "status": "BUSY"}
        try:
            # 租约到手后重新折叠：可能与上面读到的事件不同（并发裁决 / 超时扫描）
            events = await self._store.read_all(execution_id)
            state = rebuild_state(execution_id, events)
            gate = _gate_for(events, node_key)
            if gate is None:
                return {"ok": False, "status": "NOT_SUSPENDED"}
            if gate["resolved"]:
                return {"ok": True, "status": "ALREADY_RESOLVED"}
            branch = _branch_of(decision, gate["payload"])
            runtime = state.nodes.get(node_key)
            attempt = runtime.attempt if runtime and runtime.attempt else 1
            output: dict[str, Any] = {
                "branch": branch,
                "decision": branch,
                "resolution": decision,
                "resolvedBy": resolved_by,
            }
            if comment:
                output["comment"] = comment
            await self._event_log.append(
                execution_id,
                EventType.GATE_RESOLVED,
                {"decision": decision, "resolvedBy": resolved_by, "comment": comment},
                node_key=node_key,
                apply=state.apply,
            )
            await self._event_log.append(
                execution_id,
                EventType.NODE_COMPLETED,
                {
                    "attempt": attempt,
                    "output": output,
                    "outputDigest": digest_of(output),
                    "tokenUsed": 0,
                    "latencyMs": 0,
                },
                node_key=node_key,
                apply=state.apply,
            )
        finally:
            await lease.release()

        spec = await self._store.get_spec(execution_id)
        if spec is None:
            logger.error("execution %s has no spec; gate resolved without resume", execution_id)
            return {"ok": True, "status": "RESOLVED", "branch": branch, "resumed": False}
        await self.submit(ExecuteRequest.model_validate(spec))
        return {"ok": True, "status": "RESOLVED", "branch": branch, "resumed": True}

    async def _acquire_with_retry(
        self, lease: Lease, attempts: int = 4, delay_s: float = 0.25
    ) -> bool:
        for i in range(attempts):
            if await lease.acquire():
                return True
            if i < attempts - 1:
                await asyncio.sleep(delay_s)  # 上一轮执行器可能刚释放租约
        return False

    async def sweep_gates(self) -> int:
        """超时扫描：按事件流里的 timeoutAt 自动裁决（TIMEOUT）。"""
        try:
            gates = await self._store.suspended_gates()
        except Exception as exc:
            # 依赖抖动时每轮扫描都会失败：WARNING 只留一句话，堆栈降到 DEBUG，
            # 否则 PG 断连一分钟就能刷出几十屏 traceback。
            logger.warning("gate sweep scan failed: %s", exc)
            logger.debug("gate sweep scan failed", exc_info=True)
            return 0
        now = datetime.now(UTC)
        handled = 0
        for execution_id, node_key, payload in gates:
            deadline = _parse_iso(payload.get("timeoutAt"))
            if deadline is None or deadline > now:
                continue
            result = await self.resolve_gate(
                execution_id, node_key, "TIMEOUT", "system:timeout", "gate timeout"
            )
            if result.get("ok"):
                handled += 1
                logger.info(
                    "gate %s/%s timed out → %s", execution_id, node_key, result.get("branch")
                )
        return handled

    def start_gate_sweeper(self, interval_s: int | None = None) -> None:
        interval = interval_s or self._settings.gate_sweep_interval_s

        async def loop() -> None:
            while True:
                await asyncio.sleep(interval)
                try:
                    await self.sweep_gates()
                except Exception as exc:
                    logger.warning("gate sweeper failed: %s", exc)
                    logger.debug("gate sweeper failed", exc_info=True)

        self._gate_task = asyncio.create_task(loop())

    def start_sweep(self, interval_s: int = 60) -> None:
        async def loop() -> None:
            while True:
                await asyncio.sleep(interval_s)
                try:
                    for eid in await self._store.open_executions():
                        if eid not in self._tasks:
                            await self._recover_one(eid, attempts=1)
                except Exception as exc:
                    logger.warning("recovery sweep failed: %s", exc)
                    logger.debug("recovery sweep failed", exc_info=True)

        self._sweep_task = asyncio.create_task(loop())


def _gate_for(events: list[Any], node_key: str) -> dict[str, Any] | None:
    """取某节点当前的 gate 状态：挂起载荷 + 是否已裁决。

    以事件顺序为准（aggregate_version 已排序）：只有「最后一次 GateSuspended
    之后没有 GateResolved」才算仍在挂起。
    """
    payload: dict[str, Any] | None = None
    resolved = False
    for e in events:
        if e.node_key != node_key:
            continue
        if e.event_type == EventType.GATE_SUSPENDED:
            payload = dict(e.payload)
            resolved = False
        elif e.event_type == EventType.GATE_RESOLVED:
            resolved = True
            if payload is not None:
                payload = {
                    **payload,
                    "branch": _branch_of(str(e.payload.get("decision", "")), payload),
                }
    if payload is None:
        return None
    return {"payload": payload, "resolved": resolved}


def _unresolved_gate(events: list[Any]) -> tuple[str, dict[str, Any]] | None:
    """第一个仍挂起的 gate（用于 submit 短路）。"""
    keys = []
    for e in events:
        if e.event_type == EventType.GATE_SUSPENDED and e.node_key not in keys:
            keys.append(e.node_key)
    for node_key in keys:
        gate = _gate_for(events, node_key)
        if gate is not None and not gate["resolved"]:
            return node_key, gate["payload"]
    return None


def _branch_of(decision: str, gate_payload: dict[str, Any]) -> str:
    """裁决 → 分支名。TIMEOUT 按 DSL 的 onTimeout 决定（默认拒绝）。"""
    if decision == "APPROVED":
        return "approved"
    if decision == "TIMEOUT" and gate_payload.get("onTimeout") == "DEFAULT_BRANCH":
        return str(gate_payload.get("defaultBranch") or "rejected")
    return "rejected"


def _parse_iso(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _digest(obj: Any) -> str:
    return hashlib.sha256(
        json.dumps(obj, ensure_ascii=False, sort_keys=True, default=str).encode()
    ).hexdigest()
