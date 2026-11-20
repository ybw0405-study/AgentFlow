"""真实连接器（P2）：引擎经 gateway 代理调用，与 mock 分流、错误分类正确。"""

from __future__ import annotations

import httpx
import pytest

from app.commands.providers.http_tool import HttpToolConnector
from app.errors import PermanentNodeError
from app.nodes.tool import ConnectorRegistry


def _connector(handler, cid: str = "c-1") -> HttpToolConnector:
    return HttpToolConnector("http://gateway", "tok", cid, transport=httpx.MockTransport(handler))


async def test_proxies_to_internal_endpoint_and_returns_data():
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen["path"] = request.url.path
        seen["token"] = request.headers.get("x-internal-token")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json={"ok": True, "data": {"answer": 42}, "latencyMs": 17})

    result = await _connector(handler).invoke(
        call_id="e/n/1",
        operation="run",
        params={"q": "x"},
        timeout_ms=1000,
        trace_id="trace-1",
        space_id="space-a",
    )

    assert result.ok
    assert result.data == {"answer": 42}
    assert result.latency_ms == 17
    assert seen["path"] == "/internal/connectors/c-1/invoke"
    assert seen["token"] == "tok"
    assert seen["body"]["tenantId"] == "space-a"
    assert seen["body"]["callId"] == "e/n/1"


async def test_server_error_is_transient():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"ok": False, "errorCode": "CONN-HTTP-5XX"})

    result = await _connector(handler).invoke(
        call_id="e/n/2", operation="run", params={}, timeout_ms=1000, trace_id="t"
    )
    assert not result.ok
    assert result.transient


async def test_not_found_is_permanent():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={})

    result = await _connector(handler).invoke(
        call_id="e/n/3", operation="run", params={}, timeout_ms=1000, trace_id="t"
    )
    assert not result.ok
    assert result.error_code == "CONN-NOT_FOUND-001"
    assert not result.transient


async def test_timeout_is_transient():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.TimeoutException("slow")

    result = await _connector(handler).invoke(
        call_id="e/n/4", operation="run", params={}, timeout_ms=500, trace_id="t"
    )
    assert not result.ok
    assert result.error_code == "CONN-TIMEOUT"
    assert result.transient


async def test_remote_failure_body_maps_to_result():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "ok": False,
                "errorCode": "CONN-CREDENTIAL-EXPIRED",
                "transientError": False,
                "latencyMs": 3,
            },
        )

    result = await _connector(handler).invoke(
        call_id="e/n/5", operation="run", params={}, timeout_ms=1000, trace_id="t"
    )
    assert not result.ok
    assert result.error_code == "CONN-CREDENTIAL-EXPIRED"
    assert not result.transient


def test_registry_resolves_remote_connector():
    factory_calls: list[str] = []
    registry = ConnectorRegistry(lambda cid: factory_calls.append(cid) or object())

    registry.resolve("c-real", frozenset({"c-real"}))

    assert factory_calls == ["c-real"]


def test_registry_rejects_unauthorized_connector():
    registry = ConnectorRegistry(lambda cid: object())
    with pytest.raises(PermanentNodeError) as exc:
        registry.resolve("c-real", frozenset())
    assert exc.value.code == "CONN-FORBIDDEN-001"


async def test_registry_caches_connector_and_aclose_closes_resources():
    closed: list[str] = []

    class ClosableConnector:
        def __init__(self, cid: str) -> None:
            self.connector_id = cid

        async def invoke(self, **kwargs):  # pragma: no cover - 不调用
            raise AssertionError

        async def aclose(self) -> None:
            closed.append(self.connector_id)

    created: list[str] = []
    registry = ConnectorRegistry(lambda cid: created.append(cid) or ClosableConnector(cid))
    authorized = frozenset({"c-remote"})
    first = registry.resolve("c-remote", authorized)
    second = registry.resolve("c-remote", authorized)

    assert first is second  # 复用实例，不重复创建 client
    assert created == ["c-remote"]
    await registry.aclose()
    assert closed == ["c-remote"]  # 关停时底层资源被释放
    assert registry._cache == {}
