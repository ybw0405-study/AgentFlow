"""经连接器调用的 LLM provider：请求体、错误映射、模型回落、幂等键透传。

这一层的价值在于「密钥不出 connector-service」——所以测试要盯住两侧约定：
请求里只有 connector id 与内部令牌，响应解析与直连路径完全一致。
"""

from __future__ import annotations

from typing import Any

import httpx

from app.commands.providers.connector_llm import (
    ConnectorLlmFactory,
    ConnectorLlmProvider,
    map_connector_error,
)

CONNECTION_ID = "conn-deepseek-01"
INTERNAL = "dev-internal-token"


def _provider(handler: Any, **overrides: Any) -> ConnectorLlmProvider:
    opts: dict[str, Any] = {
        "gateway_base_url": "http://127.0.0.1:8080",
        "internal_token": INTERNAL,
        "connector_id": CONNECTION_ID,
        "default_model": "deepseek-chat",
        "transport": httpx.MockTransport(handler),
    }
    opts.update(overrides)
    return ConnectorLlmProvider(**opts)


def _envelope(data: dict[str, Any] | None = None, **kwargs: Any) -> dict[str, Any]:
    body: dict[str, Any] = {"ok": True, "data": data, "errorCode": None, "transientError": False}
    body.update(kwargs)
    return body


def _chat_body(text: str = "连接器返回的文本") -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", "content": text}}],
        "usage": {"prompt_tokens": 11, "completion_tokens": 5, "total_tokens": 16},
    }


async def _complete(provider: ConnectorLlmProvider, **overrides: Any) -> Any:
    kwargs: dict[str, Any] = {
        "model": "deepseek-chat",
        "prompt": "你好",
        "system": None,
        "temperature": 0.2,
        "max_tokens": 64,
        "output_mode": "text",
        "output_schema": None,
        "seed": "0" * 64,
        "trace_id": "trace-abc",
        "call_id": "call-xyz",
        "space_id": "space-alpha",
    }
    kwargs.update(overrides)
    return await provider.complete(**kwargs)


async def test_complete_parses_envelope_and_reports_usage() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen["path"] = request.url.path
        seen["token"] = request.headers.get("x-internal-token")
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_envelope(_chat_body()))

    result = await _complete(_provider(handler))

    assert result.ok is True
    assert result.data == {"text": "连接器返回的文本", "parsed": None}
    assert result.token_usage == 16
    assert result.extra["usage"] == {"prompt": 11, "completion": 5, "total": 16}
    assert result.extra["connectionId"] == CONNECTION_ID

    assert seen["path"] == f"/internal/connectors/{CONNECTION_ID}/invoke"
    assert seen["token"] == INTERNAL
    request = seen["body"]
    assert request["operation"] == "chat"
    assert request["tenantId"] == "space-alpha"
    assert request["callId"] == "call-xyz"
    assert request["params"]["model"] == "deepseek-chat"
    assert request["params"]["messages"] == [{"role": "user", "content": "你好"}]


def test_request_never_carries_endpoint_or_key() -> None:
    """画布配置里只有连接 id：请求体不得出现端点地址或任何凭据字段。"""
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        captured.update(json.loads(request.content))
        return httpx.Response(200, json=_envelope(_chat_body()))

    import asyncio

    asyncio.run(_complete(_provider(handler)))

    # 请求体只该有信封字段 + OpenAI 的 chat 参数；URL / key / header 一律不出现
    assert set(captured) == {
        "tenantId",
        "callId",
        "operation",
        "params",
        "timeoutMs",
        "traceId",
    }
    assert set(captured["params"]) == {
        "model",
        "messages",
        "temperature",
        "max_tokens",
        "stream",
    }


async def test_placeholder_model_falls_back_to_connection_default() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        import json

        seen.update(json.loads(request.content))
        return httpx.Response(200, json=_envelope(_chat_body()))

    result = await _complete(_provider(handler), model="mock-large")

    assert seen["params"]["model"] == "deepseek-chat"
    assert result.extra["model"] == "deepseek-chat"


async def test_connector_error_is_mapped_to_llm_semantics() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_envelope(None, ok=False, errorCode="CONN-CREDENTIAL-001", transientError=False),
        )

    result = await _complete(_provider(handler))

    assert result.ok is False
    assert result.error_code == "LLM-CREDENTIAL-001"
    assert result.transient is False


async def test_transient_connector_error_stays_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json=_envelope(None, ok=False, errorCode="CONN-HTTP-5XX", transientError=True)
        )

    result = await _complete(_provider(handler))

    assert result.error_code == "LLM-UPSTREAM-001"
    assert result.transient is True


async def test_missing_connection_is_permanent() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    result = await _complete(_provider(handler))

    assert result.error_code == "LLM-CONNECTION-001"
    assert result.transient is False


async def test_non_json_upstream_is_reported_not_swallowed() -> None:
    """连接器把非 JSON 响应包成 {"raw": ...}，此时不能当成空内容静默成功。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_envelope({"raw": "<html>oops</html>"}))

    result = await _complete(_provider(handler))

    assert result.ok is False
    assert result.error_code == "LLM-RESPONSE-001"


async def test_json_mode_parses_content() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_envelope(_chat_body('{"answer": "42"}')))

    result = await _complete(
        _provider(handler),
        output_mode="json",
        output_schema={"type": "object", "properties": {"answer": {"type": "string"}}},
    )

    assert result.ok is True
    assert result.data is not None
    assert result.data["parsed"] == {"answer": "42"}


def test_error_map_is_total_for_unknown_codes() -> None:
    assert map_connector_error("CONN-UNKNOWN-999", True) == ("CONN-UNKNOWN-999", True)
    assert map_connector_error("", False) == ("LLM-UPSTREAM-001", False)


async def test_factory_reuses_one_provider_per_connection() -> None:
    factory = ConnectorLlmFactory(gateway_base_url="http://127.0.0.1:8080", internal_token=INTERNAL)
    first = factory.for_connection("c1")
    assert factory.for_connection("c1") is first
    assert factory.for_connection("c2") is not first
    await factory.aclose()
