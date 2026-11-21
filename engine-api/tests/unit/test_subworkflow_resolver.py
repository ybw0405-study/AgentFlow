"""子工作流解析器：网络瞬时故障必须可重试，确定性失败保持永久。"""

from __future__ import annotations

import httpx
import pytest

from app.errors import PermanentNodeError, TransientNodeError
from app.nodes.subworkflow_resolver import GatewaySubworkflowResolver


def _resolver(handler) -> GatewaySubworkflowResolver:
    resolver = GatewaySubworkflowResolver("http://gateway", "tok")
    # 构造器未暴露 transport 注入点，直接替换底层 client 以注入 MockTransport
    resolver._client = httpx.AsyncClient(
        base_url="http://gateway", transport=httpx.MockTransport(handler)
    )
    return resolver


async def test_connection_error_is_transient():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("gateway down")

    with pytest.raises(TransientNodeError) as exc:
        await _resolver(handler).resolve(definition_id="wf-1", space_id="s1", version_id=None)
    assert exc.value.code == "SUBWF-UNAVAILABLE-001"
    assert exc.value.transient is True


async def test_not_found_is_permanent():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    with pytest.raises(PermanentNodeError) as exc:
        await _resolver(handler).resolve(definition_id="wf-1", space_id="s1", version_id=None)
    assert exc.value.code == "SUBWF-NOT-FOUND-001"


async def test_success_returns_dsl():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"data": {"dsl": {"dslVersion": "1.0"}}})

    dsl = await _resolver(handler).resolve(definition_id="wf-1", space_id="s1", version_id=None)
    assert dsl == {"dslVersion": "1.0"}
