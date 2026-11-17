"""tool 节点：参数渲染 → 授权校验 → ToolCommand（经连接器，callId 幂等）。

P2：连接器注册表同时支持内置 mock.*（离线演示）与真实连接器 —— 后者经
gateway → connector-service 执行，凭据只在 connector-service 解密。
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
    ) -> CommandResult: ...


class ConnectorRegistry:
    """按 connectorId 解析连接器：mock.* 内置，其余交给远端连接器服务。"""

    def __init__(
        self,
        mock_factory: Callable[[str], ToolConnector],
        remote_factory: Callable[[str], ToolConnector] | None = None,
    ) -> None:
        self._mock_factory = mock_factory
        self._remote_factory = remote_factory

    def resolve(self, connector_id: str, authorized: frozenset[str]) -> ToolConnector:
        if connector_id not in authorized:
            raise PermanentNodeError(
                "CONN-FORBIDDEN-001",
                f"connector '{connector_id}' is not authorized for this execution",
            )
        if connector_id.startswith("mock."):
            return self._mock_factory(connector_id)
        if self._remote_factory is None:
            raise PermanentNodeError("CONN-NOT_FOUND-001", f"connector '{connector_id}' not found")
        return self._remote_factory(connector_id)


class ToolExecutor:
    node_type = "tool"

    def __init__(self, connectors: ConnectorRegistry) -> None:
        self._connectors = connectors

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
