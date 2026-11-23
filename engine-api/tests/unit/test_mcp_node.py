"""mcp 节点：定位参数渲染、mcpMethod 下发、授权拒绝、错误分类与配置互斥校验。"""

from __future__ import annotations

import json
import uuid
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from app.commands.base import CommandResult
from app.commands.providers.http_tool import HttpToolConnector
from app.dsl.models import McpConfig, McpNode
from app.errors import PermanentNodeError, TransientNodeError
from app.events.types import EventType
from app.nodes.base import NodeContext, Policy
from app.nodes.mcp import McpExecutor
from app.nodes.tool import ConnectorRegistry


class FakeMcp:
    def __init__(self, result: CommandResult | None = None):
        self.connector_id = "test.mcp"
        self.calls: list[dict[str, Any]] = []
        self.result = result or CommandResult(ok=True, data={"text": "hello", "contents": []})

    async def invoke(self, **kwargs: Any) -> CommandResult:
        self.calls.append(kwargs)
        return self.result


def _node(capability: str = "resource", **config: Any) -> McpNode:
    base: dict[str, Any] = {"connector_id": "test.mcp", "capability": capability}
    if capability == "resource":
        base["uri"] = config.pop("uri", "{{ vars.doc }}")
    else:
        base["prompt_name"] = config.pop("prompt_name", "greet")
        base["arguments_mapping"] = config.pop("arguments_mapping", {"who": "{{ vars.who }}"})
    base.update(config)
    return McpNode(key="fetch", type="mcp", config=McpConfig(**base))


def _ctx() -> tuple[NodeContext, list[tuple[Any, dict[str, Any]]]]:
    events: list[tuple[Any, dict[str, Any]]] = []

    async def emit(event_type: Any, payload: dict[str, Any], *, node_key: str | None = None):
        events.append((event_type, payload))

    ctx = NodeContext(
        execution_id=uuid.uuid4(),
        node_key="fetch",
        attempt=1,
        seed="seed",
        trace_id="trace-1",
        variables={"doc": "test://doc", "who": "acme"},
        variables_spec={},
        outputs={},
        node_statuses={},
        policy=Policy(),
        budget=None,
        emit=emit,
        authorized_connector_ids=frozenset({"test.mcp"}),
        space_id="space-1",
    )
    return ctx, events


@pytest.mark.asyncio
async def test_resource_renders_uri_and_declares_method():
    fake = FakeMcp()
    ctx, events = _ctx()
    result = await McpExecutor(ConnectorRegistry(lambda cid: fake)).execute(_node(), ctx)
    assert fake.calls[0]["mcp_method"] == "resources/read"
    assert fake.calls[0]["params"] == {"uri": "test://doc"}
    assert result.output["result"]["text"] == "hello"
    assert [e[0] for e in events] == [EventType.COMMAND_ISSUED, EventType.COMMAND_COMPLETED]


@pytest.mark.asyncio
async def test_prompt_renders_arguments_and_declares_method():
    fake = FakeMcp()
    ctx, _ = _ctx()
    await McpExecutor(ConnectorRegistry(lambda cid: fake)).execute(_node("prompt"), ctx)
    assert fake.calls[0]["mcp_method"] == "prompts/get"
    assert fake.calls[0]["params"] == {"name": "greet", "arguments": {"who": "acme"}}


@pytest.mark.asyncio
async def test_unauthorized_connector_is_permanent():
    ctx, _ = _ctx()
    ctx.authorized_connector_ids = frozenset()
    with pytest.raises(PermanentNodeError) as exc:
        await McpExecutor(ConnectorRegistry(lambda cid: FakeMcp())).execute(_node(), ctx)
    assert exc.value.code == "CONN-FORBIDDEN-001"


@pytest.mark.asyncio
async def test_transient_and_permanent_classification():
    ctx, _ = _ctx()
    transient = FakeMcp(CommandResult(ok=False, error_code="CONN-TIMEOUT", transient=True))
    with pytest.raises(TransientNodeError):
        await McpExecutor(ConnectorRegistry(lambda cid: transient)).execute(_node(), ctx)
    permanent = FakeMcp(CommandResult(ok=False, error_code="CONN-MCP-001", transient=False))
    with pytest.raises(PermanentNodeError):
        await McpExecutor(ConnectorRegistry(lambda cid: permanent)).execute(_node(), ctx)


def test_config_locator_mutually_exclusive():
    with pytest.raises(ValidationError):
        McpConfig(connector_id="c", capability="resource")
    with pytest.raises(ValidationError):
        McpConfig(connector_id="c", capability="resource", uri="u", prompt_name="p")
    with pytest.raises(ValidationError):
        McpConfig(connector_id="c", capability="prompt")
    with pytest.raises(ValidationError):
        McpConfig(connector_id="c", capability="prompt", prompt_name="p", uri="u")


@pytest.mark.asyncio
async def test_http_tool_sends_mcp_method_only_when_set():
    seen: list[dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"ok": True, "data": {}, "latencyMs": 1})

    connector = HttpToolConnector(
        "http://gw", "tok", "test.mcp", transport=httpx.MockTransport(handler)
    )
    await connector.invoke(
        call_id="c1", operation="op", params={}, timeout_ms=1000, trace_id="t",
        mcp_method="resources/read",
    )
    await connector.invoke(call_id="c2", operation="op", params={}, timeout_ms=1000, trace_id="t")
    assert seen[0]["mcpMethod"] == "resources/read"
    assert "mcpMethod" not in seen[1]
    await connector.aclose()
