"""mcp 节点：MCP 非工具能力（resources/read、prompts/get），经连接器出网。

与 tool 节点同一条治理链路：授权校验 → callId 幂等 → 事件成对 → 瞬时/永久分类。
区别只在下发帧：mcpMethod 显式声明方法，定位参数（uri / name+arguments）由配置渲染。
tools/call 仍归 tool 节点，避免一个节点类型承载两种语义。
"""

from __future__ import annotations

from ..commands.base import CommandKind, digest_of, make_call_id
from ..dsl.models import McpNode
from ..errors import PermanentNodeError, TransientNodeError
from ..events.types import EventType
from ..expression.template import render_mapping
from .base import NodeContext, NodeResult
from .tool import ConnectorRegistry


class McpExecutor:
    node_type = "mcp"

    def __init__(self, connectors: ConnectorRegistry) -> None:
        self._connectors = connectors

    async def aclose(self) -> None:
        await self._connectors.aclose()

    async def execute(self, node: McpNode, ctx: NodeContext) -> NodeResult:
        cfg = node.config
        connector = self._connectors.resolve(cfg.connector_id, ctx.authorized_connector_ids)
        if cfg.capability == "resource":
            params = {"uri": render_mapping({"uri": cfg.uri or ""}, ctx.template_context())["uri"]}
            method = "resources/read"
            operation = params["uri"]
        else:
            arguments = render_mapping(cfg.arguments_mapping, ctx.template_context())
            params = {"name": cfg.prompt_name or "", "arguments": arguments}
            method = "prompts/get"
            operation = params["name"]

        call_id = make_call_id(ctx.execution_id, ctx.node_key, ctx.attempt)
        await ctx.emit(
            EventType.COMMAND_ISSUED,
            {
                "callId": call_id,
                "kind": str(CommandKind.TOOL),
                "connectorId": cfg.connector_id,
                "operation": operation,
                "requestDigest": digest_of(params),
                "attempt": ctx.attempt,
            },
        )
        timeout_ms = node.timeout_ms or 30_000
        try:
            result = await connector.invoke(
                call_id=call_id,
                operation=operation,
                params=params,
                timeout_ms=timeout_ms,
                trace_id=ctx.trace_id,
                space_id=ctx.space_id,
                mcp_method=method,
            )
        except TransientNodeError as exc:
            await ctx.emit(EventType.COMMAND_FAILED, {"callId": call_id, "errorCode": exc.code})
            raise

        if not result.ok:
            await ctx.emit(
                EventType.COMMAND_FAILED,
                {"callId": call_id, "errorCode": result.error_code or "MCP-UNKNOWN"},
            )
            error_cls = TransientNodeError if result.transient else PermanentNodeError
            raise error_cls(result.error_code or "MCP-UNKNOWN", "mcp command failed")

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
                "capability": cfg.capability,
                "params": params,
            },
        )
