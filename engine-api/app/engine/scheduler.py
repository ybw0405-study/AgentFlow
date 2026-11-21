"""拓扑分层调度（§8.3）+ 取消/deadline/步数/预算约束（§8.4）+ 租约纪律（§8.5）。"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from datetime import UTC, datetime
from typing import Any

import redis.asyncio as aioredis

from ..config import Settings
from ..dsl.compile import compile_dag
from ..dsl.models import WorkflowDsl
from ..errors import (
    BudgetExhaustedError,
    DeadlineExceededError,
    EngineError,
    ExecutionCancelledError,
    GateSuspendedSignal,
    PermanentNodeError,
)
from ..events.log import EventLog
from ..events.store import EventStore
from ..events.types import EventType
from ..nodes.base import Policy
from ..nodes.registry import NodeExecutor
from ..observability.metrics import BUDGET_STOP_TOTAL, NODE_FAILED_TOTAL
from .executor import NodeRunner, checkpoint_payload
from .lease import Lease
from .state import ExecutionState

logger = logging.getLogger(__name__)


class LeaseLostError(Exception):
    """租约丢失：必须静默停止，禁止再写任何事件（§8.5）。"""


def _deadline_from(state: ExecutionState, policy: Policy) -> float | None:
    """把绝对截止时刻换算成本进程的 monotonic 时间点。

    基于 ExecutionCreated 的落库时间而非本次运行起点：崩溃恢复、审批回填后的续跑
    都不应重置总超时预算，否则挂起越久、可用时间反而越长。
    """
    if not policy.deadline_ms:
        return None
    now_wall = datetime.now(UTC)
    created = state.created_at
    if created is None:
        return time.monotonic() + policy.deadline_ms / 1000
    if created.tzinfo is None:
        created = created.replace(tzinfo=UTC)
    remaining_ms = policy.deadline_ms - (now_wall - created).total_seconds() * 1000
    return time.monotonic() + max(remaining_ms, 0) / 1000


def _edge_active(edge: Any, state: ExecutionState) -> bool:
    """入边是否激活：来源 SUCCEEDED 且（无 branch 约束或分支命中）。

    带 branch 的边只从 condition/human_gate 出发（validate 保证）；
    来源节点的 NodeCompleted 输出含 branch 字段，据此判定命中。
    """
    src = state.nodes.get(edge.from_)
    if src is None or src.status != "SUCCEEDED":
        return False
    if edge.branch is None:
        return True
    return (src.output or {}).get("branch") == edge.branch


class Scheduler:
    def __init__(
        self,
        settings: Settings,
        event_log: EventLog,
        store: EventStore,
        redis: aioredis.Redis,
        executors: dict[str, NodeExecutor],
        budget: Any,
        worker_id: str,
    ) -> None:
        self._settings = settings
        self._event_log = event_log
        self._redis = redis
        self._budget = budget
        self._worker_id = worker_id
        self._runner = NodeRunner(event_log, store, executors, settings.checkpoint_interval)

    async def _emit(
        self,
        execution_id: uuid.UUID,
        state: ExecutionState,
        node_key: str | None,
        event_type: str,
        payload: dict[str, Any],
    ):
        ev = await self._event_log.append(
            execution_id, event_type, payload, node_key=node_key, apply=state.apply
        )
        if state.events_since_checkpoint >= self._settings.checkpoint_interval:
            await self._event_log.append(
                execution_id,
                EventType.CHECKPOINT_CREATED,
                checkpoint_payload(state),
                apply=state.apply,
            )
            await self._budget.reconcile(execution_id, state.token_used)
        return ev

    async def _check_stop_conditions(
        self,
        execution_id: uuid.UUID,
        state: ExecutionState,
        deadline_at: float | None,
        lease: Lease,
    ) -> None:
        if state.lease_lost:
            raise LeaseLostError(str(execution_id))
        # Redis 故障与查询结果必须分开处理：查询拿到明确结果（租约不在我手上 /
        # 有取消标记）才走对应语义；连接不可达时无法确认租约，按「租约疑似丢失」
        # 静默停止让恢复扫描接管（与续约循环的 P1-6 语义一致），绝不传播成普通
        # 异常把活执行落成 ExecutionFailed 终态（P1-7）。
        try:
            held = await lease.held_by_me()
        except Exception as exc:
            logger.warning(
                "lease check unavailable for %s; stopping as lease lost: %s", execution_id, exc
            )
            state.lease_lost = True
            raise LeaseLostError(str(execution_id)) from exc
        if not held:
            state.lease_lost = True
            raise LeaseLostError(str(execution_id))
        try:
            cancelled = await self._redis.get(f"af:cancel:{execution_id}")
        except Exception as exc:
            logger.warning(
                "cancel flag unavailable for %s; stopping as lease lost: %s", execution_id, exc
            )
            state.lease_lost = True
            raise LeaseLostError(str(execution_id)) from exc
        if cancelled:
            raise ExecutionCancelledError()
        if deadline_at is not None and time.monotonic() > deadline_at:
            raise DeadlineExceededError()

    async def _run_one(
        self,
        *,
        key: str,
        sem: asyncio.Semaphore,
        dag,
        state: ExecutionState,
        dsl: WorkflowDsl,
        execution_id: uuid.UUID,
        trace_id: str,
        policy: Policy,
        deadline_at: float | None,
        lease: Lease,
        authorized_connector_ids: frozenset[str],
        space_id: str,
    ) -> None:
        async with sem:
            await self._check_stop_conditions(execution_id, state, deadline_at, lease)
            if policy.max_steps is not None and state.steps_used >= policy.max_steps:
                raise PermanentNodeError(
                    "EXEC-MAX_STEPS_EXCEEDED", f"max steps {policy.max_steps} exceeded"
                )
            await self._runner.run(
                node=dag.nodes[key],
                dag=dag,
                state=state,
                dsl=dsl,
                execution_id=execution_id,
                seed=state.seed,
                trace_id=trace_id,
                policy=policy,
                budget=self._budget,
                authorized_connector_ids=authorized_connector_ids,
                space_id=space_id,
            )

    async def run(
        self,
        *,
        execution_id: uuid.UUID,
        dsl: WorkflowDsl,
        state: ExecutionState,
        trace_id: str,
        policy: Policy,
        authorized_connector_ids: frozenset[str],
        lease: Lease,
        space_id: str = "",
    ) -> str:
        dag = compile_dag(dsl)
        started_mono = time.monotonic()
        # 恢复扫描可能捞到「仍在等审批」的执行：有 SUSPENDED 节点就不要再跑，
        # 否则会重复落 GateSuspended（裁决回调才是唯一续跑入口）。
        if any(n.status == "SUSPENDED" for n in state.nodes.values()):
            return "SUSPENDED"
        if state.status == "PENDING":
            await self._emit(
                execution_id,
                state,
                None,
                EventType.EXECUTION_STARTED,
                {"workerId": self._worker_id},
            )
        deadline_at = _deadline_from(state, policy)
        try:
            for layer in dag.layers:
                await self._check_stop_conditions(execution_id, state, deadline_at, lease)
                ready: list[str] = []
                skipped: list[str] = []
                for key in layer:
                    nr = state.nodes.get(key)
                    if nr is not None and nr.status in ("SUCCEEDED", "SKIPPED"):
                        continue  # 重放：已完成节点直接注入输出，不重新执行（§8.2.1）
                    in_edges = dag.in_edges.get(key, [])
                    # 无入边（start）：直接就绪
                    if not in_edges:
                        ready.append(key)
                        continue
                    # 所有入边来源已落终态（SUCCEEDED 或 SKIPPED）才做分支判定
                    all_settled = all(
                        (state.nodes.get(e.from_).status in ("SUCCEEDED", "SKIPPED"))
                        if e.from_ in state.nodes
                        else False
                        for e in in_edges
                    )
                    if not all_settled:
                        continue  # 理论不发生：分层保证上游已处理；防御性等待
                    # 任一入边激活（来源成功且分支命中）→ 执行；否则整节点 SKIPPED
                    if any(_edge_active(e, state) for e in in_edges):
                        ready.append(key)
                    else:
                        skipped.append(key)
                for key in skipped:
                    await self._emit(
                        execution_id,
                        state,
                        key,
                        EventType.NODE_SKIPPED,
                        {"reason": "BRANCH_NOT_TAKEN_OR_UPSTREAM_SKIPPED"},
                    )

                sem = asyncio.Semaphore(dsl.defaults.max_parallelism)
                results = await asyncio.gather(
                    *[
                        self._run_one(
                            key=k,
                            sem=sem,
                            dag=dag,
                            state=state,
                            dsl=dsl,
                            execution_id=execution_id,
                            trace_id=trace_id,
                            policy=policy,
                            deadline_at=deadline_at,
                            lease=lease,
                            authorized_connector_ids=authorized_connector_ids,
                            space_id=space_id,
                        )
                        for k in ready
                    ],
                    return_exceptions=True,
                )
                failures = [r for r in results if isinstance(r, BaseException)]
                if failures:
                    # gather(return_exceptions=True) 只抛第一个会丢掉其它分支的诊断，
                    # 先把全部异常记进日志再抛首个（C-8）。
                    for exc in failures:
                        logger.error("parallel branch failed execution=%s: %r", execution_id, exc)
                    raise failures[0]

            # 收口只认真正跑成功的 end：分支工作流里 end 可以有多个，
            # 取「定义里的第一个」会把结果永远钉在被 SKIPPED 的那个上（返回空对象）。
            result: dict[str, Any] = {}
            for end_key in dag.end_keys():
                rt = state.nodes.get(end_key)
                if rt is None or rt.status != "SUCCEEDED":
                    continue
                produced = (rt.output or {}).get("result")
                if isinstance(produced, dict):
                    result.update(produced)
            await self._emit(
                execution_id,
                state,
                None,
                EventType.EXECUTION_COMPLETED,
                {
                    "result": result,
                    "tokenUsed": state.token_used,
                    "stepsUsed": state.steps_used,
                    "durationMs": int((time.monotonic() - started_mono) * 1000),
                },
            )
            return "SUCCEEDED"
        except LeaseLostError:
            logger.error("lease lost mid-run for %s; stopped writing events", execution_id)
            return "LEASE_LOST"  # 不落任何事件；接管方负责续跑
        except ExecutionCancelledError as exc:
            await self._emit(
                execution_id, state, None, EventType.EXECUTION_CANCELLED, {"reason": exc.message}
            )
            return "CANCELLED"
        except DeadlineExceededError:
            await self._emit(
                execution_id, state, None, EventType.EXECUTION_TIMED_OUT, {"phase": "RUNNING"}
            )
            return "TIMED_OUT"
        except GateSuspendedSignal:
            # 挂起：不落任何执行级终态事件；tracker 保持 OPEN，裁决回调负责续跑。
            return "SUSPENDED"
        except BudgetExhaustedError as exc:
            BUDGET_STOP_TOTAL.inc()
            await self._emit(
                execution_id,
                state,
                None,
                EventType.EXECUTION_FAILED,
                {
                    "errorCode": exc.code,
                    "budgetStatus": "BUDGET_EXHAUSTED",
                    "message": exc.message[:500],
                    "tokenUsed": state.token_used,
                    "stepsUsed": state.steps_used,
                },
            )
            return "FAILED"
        except EngineError as exc:
            NODE_FAILED_TOTAL.labels(node_type="-", error_code=exc.code).inc()
            await self._emit(
                execution_id,
                state,
                None,
                EventType.EXECUTION_FAILED,
                {
                    "errorCode": exc.code,
                    "message": exc.message[:500],
                    "tokenUsed": state.token_used,
                    "stepsUsed": state.steps_used,
                },
            )
            return "FAILED"
        except Exception as exc:
            logger.exception("execution %s scheduler crashed", execution_id)
            await self._emit(
                execution_id,
                state,
                None,
                EventType.EXECUTION_FAILED,
                {
                    "errorCode": "NODE-INTERNAL-001",
                    "message": str(exc)[:500],
                    "tokenUsed": state.token_used,
                    "stepsUsed": state.steps_used,
                },
            )
            return "FAILED"
