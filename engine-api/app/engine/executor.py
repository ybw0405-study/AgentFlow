"""单节点执行：调度事件 + 重试 + 超时 + checkpoint 触发（§8.3 / §8.4）。

错误分类：瞬时（超时/限流/5xx）指数退避 ≤ maxAttempts；永久（DSL/参数/权限/PII/
表达式）不重试。tool 节点 idempotent=false 时强制不重试。
"""

from __future__ import annotations

import asyncio
import copy
import logging
import time
import uuid
from typing import Any

from ..commands.base import digest_of
from ..dsl.compile import Dag
from ..dsl.models import AnyNode, WorkflowDsl
from ..errors import (
    EngineError,
    GateSuspendedSignal,
    PermanentNodeError,
    TransientNodeError,
)
from ..events.log import EventLog
from ..events.store import EventStore
from ..events.types import EventType
from ..nodes.base import NodeContext, NodeResult, Policy
from ..nodes.registry import NodeExecutor
from ..nodes.subgraph import SubgraphRunner
from ..observability.tracing import node_span
from .state import ExecutionState

logger = logging.getLogger(__name__)

RETRYABLE_NODE_TYPES = frozenset(
    {"llm", "retrieval", "tool", "mcp", "loop", "parallel", "subworkflow"}
)


def checkpoint_payload(state: ExecutionState) -> dict[str, Any]:
    return {
        "completedNodes": sorted(k for k, n in state.nodes.items() if n.status == "SUCCEEDED"),
        # 必须深拷贝：事件落库后 state.apply 会用同一份快照复原变量，
        # 若 payload 直接引用 state.variables，apply 的 clear() 会把快照清空，
        # 变量在写完 checkpoint 的瞬间整体丢失（下游节点看到空 vars）。
        "variablesSnapshot": copy.deepcopy(state.variables),  # 入网前已脱敏（§5.3）
        "tokenUsed": state.token_used,
        "stepsUsed": state.steps_used,
        "version": state.version,
    }


class NodeRunner:
    def __init__(
        self,
        event_log: EventLog,
        store: EventStore,
        executors: dict[str, NodeExecutor],
        checkpoint_interval: int,
    ) -> None:
        self._event_log = event_log
        self._store = store
        self._executors = executors
        self._checkpoint_interval = checkpoint_interval

    async def run(
        self,
        *,
        node: AnyNode,
        dag: Dag,
        state: ExecutionState,
        dsl: WorkflowDsl,
        execution_id: uuid.UUID,
        seed: str,
        trace_id: str,
        policy: Policy,
        budget: Any,
        authorized_connector_ids: frozenset[str],
        space_id: str = "",
        variables_override: dict[str, Any] | None = None,
        outputs_override: dict[str, dict[str, Any]] | None = None,
    ) -> None:
        """成功返回；最终失败抛 EngineError 交给调度器。

        variables_override 供子图并发迭代使用：各迭代拿独立变量副本，
        避免共享 state.variables 造成竞态（§8.3 loop 并发度）。
        outputs_override 供子图节点以原始 key 引用兄弟节点输出（命名空间映射）。
        """
        retry = node.retry or dsl.defaults.retry
        retryable = node.type in RETRYABLE_NODE_TYPES
        if node.type in ("tool", "mcp") and not node.config.idempotent:  # type: ignore[attr-defined]
            retryable = False
        max_attempts = retry.max_attempts if retryable else 1
        timeout_ms = node.timeout_ms or dsl.defaults.timeout_ms

        async def emit(event_type: str, payload: dict[str, Any], *, node_key: str | None = None):
            ev = await self._event_log.append(
                execution_id, event_type, payload, node_key=node_key or node.key, apply=state.apply
            )
            if state.events_since_checkpoint >= self._checkpoint_interval:
                await self._event_log.append(
                    execution_id,
                    EventType.CHECKPOINT_CREATED,
                    checkpoint_payload(state),
                    apply=state.apply,
                )
                # checkpoint 顺带对账 Redis 预占，避免长执行累计泄漏
                await budget.reconcile(execution_id, state.token_used)
            return ev

        await emit(
            EventType.NODE_SCHEDULED,
            {"nodeType": node.type, "attempt": 1, "dependsOn": dag.dependencies_of(node.key)},
        )

        with node_span(
            f"{node.type} {node.key}",
            {
                "execution_id": str(execution_id),
                "node_key": node.key,
                "node_type": node.type,
                # 只取配置的模型名；渲染后的 prompt 与输出文本一律不进 span
                **({"model": node.config.model} if node.type == "llm" else {}),
            },
        ) as span:
            for attempt in range(1, max_attempts + 1):
                ctx = NodeContext(
                    execution_id=execution_id,
                    node_key=node.key,
                    attempt=attempt,
                    seed=seed,
                    trace_id=trace_id,
                    variables=variables_override
                    if variables_override is not None
                    else state.variables,
                    variables_spec=dsl.variables,
                    outputs=outputs_override
                    if outputs_override is not None
                    else state.completed_outputs(),
                    node_statuses=state.node_statuses(),
                    policy=policy,
                    budget=budget,
                    emit=emit,
                    authorized_connector_ids=authorized_connector_ids,
                    space_id=space_id,
                )
                ctx.subgraph = SubgraphRunner(self, state, ctx, dsl)
                await emit(EventType.NODE_STARTED, {"attempt": attempt})
                started = time.monotonic()
                result: NodeResult | None = None
                error: EngineError | None = None
                try:
                    async with asyncio.timeout(timeout_ms / 1000):
                        result = await self._executors[node.type].execute(node, ctx)
                except GateSuspendedSignal as signal:
                    # human_gate：挂起事件先落库，再原样上抛给调度器。
                    # 不进入 NodeFailed/重试分支 —— 挂起不是失败。
                    span.set_attribute("outcome", "SUSPENDED")
                    span.set_attribute("attempts", attempt)
                    await emit(
                        EventType.NODE_SUSPENDED,
                        {"reason": "HUMAN_GATE", "attempt": attempt},
                    )
                    await emit(EventType.GATE_SUSPENDED, signal.payload)
                    # 强制落 checkpoint：变量只活在内存与快照里，不落快照就等于
                    # 裁决续跑时丢掉 upstream transform 的结果（会静默用回初始值）。
                    await self._event_log.append(
                        execution_id,
                        EventType.CHECKPOINT_CREATED,
                        checkpoint_payload(state),
                        apply=state.apply,
                    )
                    await budget.reconcile(execution_id, state.token_used)
                    raise
                except TimeoutError:
                    error = TransientNodeError("NODE-TIMEOUT", f"node exceeded {timeout_ms}ms")
                except EngineError as exc:
                    error = exc
                except Exception as exc:  # 未分类异常按永久失败处理，不盲目重试
                    logger.exception("node %s crashed unexpectedly", node.key)
                    error = PermanentNodeError("NODE-INTERNAL-001", str(exc)[:300])

                latency_ms = int((time.monotonic() - started) * 1000)
                if error is None and result is not None:
                    span.set_attribute("outcome", "SUCCEEDED")
                    span.set_attribute("attempts", attempt)
                    span.set_attribute("token_used", result.token_used)
                    span.set_attribute("latency_ms", latency_ms)
                    await self._store.put_node_io(
                        execution_id, node.key, "IN", result.input_snapshot or {}
                    )
                    await emit(
                        EventType.NODE_COMPLETED,
                        {
                            "attempt": attempt,
                            "output": result.output,
                            "outputDigest": digest_of(result.output),
                            "tokenUsed": result.token_used,
                            "latencyMs": latency_ms,
                        },
                    )
                    return

                if error is None:  # 防御：error 与 result 不会同时为空
                    raise PermanentNodeError("NODE-INTERNAL-001", "missing node error context")
                retry_left = error.transient and attempt < max_attempts
                await emit(
                    EventType.NODE_FAILED,
                    {
                        "attempt": attempt,
                        "errorCode": error.code,
                        "class": "TRANSIENT" if error.transient else "PERMANENT",
                        "retryable": retry_left,
                        "message": error.message[:500],
                        "latencyMs": latency_ms,
                    },
                )
                if retry_left:
                    delay_ms = int(retry.backoff_ms * (retry.backoff_multiplier ** (attempt - 1)))
                    await emit(
                        EventType.NODE_RETRY_SCHEDULED,
                        {"nextAttempt": attempt + 1, "delayMs": delay_ms, "cause": error.code},
                    )
                    await asyncio.sleep(delay_ms / 1000)
                    continue
                # 失败只记错误码：error.message 可能含用户文本，不进 span
                span.set_attribute("outcome", error.code)
                span.set_attribute("attempts", attempt)
                span.set_attribute("latency_ms", latency_ms)
                raise error
