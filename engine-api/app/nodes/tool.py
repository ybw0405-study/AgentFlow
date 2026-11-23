"""tool 节点：参数渲染 → 授权校验 → ToolCommand（经连接器，callId 幂等）。

连接器只有一条来源：gateway → connector-service。凭据只在 connector-service 解密，
引擎拿不到明文；未授权的 connectorId 在授权校验处直接拒绝。
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, Protocol

from ..commands.base import CommandKind, CommandResult, digest_of, make_call_id
from ..dsl.models import ToolNode
from ..errors import PermanentNodeError, TransientNodeError
from ..events.types import EventType
from ..expression.template import render_mapping
from .base import NodeContext, NodeResult


class ToolConnector(Protocol):
    connector_id: str

    async def invoke(
        self,
        *,
        call_id: str,
        operation: str,
        params: dict[str, Any],
        timeout_ms: int,
        trace_id: str,
        space_id: str = "",
        mcp_method: str | None = None,
    ) -> CommandResult: ...


class ConnectorRegistry:
    """按 connectorId 解析连接器，全部交给远端连接器服务。"""

    def __init__(
        self,
        remote_factory: Callable[[str], ToolConnector] | None = None,
    ) -> None:
        self._remote_factory = remote_factory
        # 缓存已创建的连接器实例：避免每次 resolve 新建 AsyncClient 造成句柄泄漏，
        # 并在关停时统一释放底层资源。
        self._cache: dict[str, ToolConnector] = {}

    def resolve(self, connector_id: str, authorized: frozenset[str]) -> ToolConnector:
        if connector_id not in authorized:
            raise PermanentNodeError(
                "CONN-FORBIDDEN-001",
                f"connector '{connector_id}' is not authorized for this execution",
            )
        cached = self._cache.get(connector_id)
        if cached is not None:
            return cached
        if self._remote_factory is not None:
            connector = self._remote_factory(connector_id)
        else:
            raise PermanentNodeError("CONN-NOT_FOUND-001", f"connector '{connector_id}' not found")
        self._cache[connector_id] = connector
        return connector

    async def aclose(self) -> None:
        """释放所有持有底层连接资源的连接器（如 httpx.AsyncClient）。"""
        for connector in self._cache.values():
            closer = getattr(connector, "aclose", None)
            if closer is not None:
                await closer()
        self._cache.clear()


class ToolExecutor:
    node_type = "tool"

    def __init__(self, connectors: ConnectorRegistry) -> None:
        self._connectors = connectors

    async def aclose(self) -> None:
        await self._connectors.aclose()

    async def execute(self, node: ToolNode, ctx: NodeContext) -> NodeResult:
        cfg = node.config
        params = render_mapping(cfg.params_mapping, ctx.template_context())
        connector = self._connectors.resolve(cfg.connector_id, ctx.authorized_connector_ids)

        call_id = make_call_id(ctx.execution_id, ctx.node_key, ctx.attempt)
        await ctx.emit(
            EventType.COMMAND_ISSUED,
            {
                "callId": call_id,
                "kind": str(CommandKind.TOOL),
                "connectorId": cfg.connector_id,
                "operation": cfg.operation,
                "requestDigest": digest_of(params),
                "attempt": ctx.attempt,
            },
        )
        timeout_ms = node.timeout_ms or 30_000
        try:
            result = await connector.invoke(
                call_id=call_id,
                operation=cfg.operation,
                params=params,
                timeout_ms=timeout_ms,
                trace_id=ctx.trace_id,
                space_id=ctx.space_id,
            )
        except TransientNodeError as exc:
            await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": exc.code})
            raise

        if not result.ok:
            await ctx.emit(
                EventType.COMMAND_FAILED,
                {"callId": call_id, "errorCode": result.error_code or "TOOL-UNKNOWN"},
            )
            error_cls = TransientNodeError if result.transient else PermanentNodeError
            raise error_cls(result.error_code or "TOOL-UNKNOWN", "tool command failed")

        await ctx.emit(
            EventType.COMMAND_COMPLETED,
            {
                "callId": call_id,
                "responseDigest": digest_of(result.data),
                "latencyMs": result.latency_ms,
            },
        )
        return NodeResult(
            output={
                "result": result.data,
                "callId": call_id,
                "latencyMs": result.latency_ms,
            },
            input_snapshot={
                "connectorId": cfg.connector_id,
                "operation": cfg.operation,
                "params": params,
            },
        )
