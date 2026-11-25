"""OpenAI 兼容真实模型 provider：请求构造、错误分类、凭据脱敏、全链路执行。"""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx
import pytest

from app.commands.providers.openai_compatible import OpenAICompatibleProvider
from app.config import Settings, _guard_production
from app.engine.requests import ExecuteRequest
from app.events.types import EXECUTION_STATUS_OF_EVENT, TERMINAL_EVENTS

from .conftest import make_execute_request, make_stack


async def wait_terminal(store, execution_id, timeout_s: float = 10.0) -> str:
    import asyncio

    for _ in range(int(timeout_s / 0.05)):
        events = await store.read_all(execution_id)
        terminal = [e for e in events if e.event_type in TERMINAL_EVENTS]
        if terminal:
            return str(EXECUTION_STATUS_OF_EVENT[terminal[-1].event_type])
        await asyncio.sleep(0.05)
    raise AssertionError(f"execution {execution_id} did not reach terminal in time")


BASE = "https://llm.example.test/v1"
KEY = "sk-unit-test-secret-DO-NOT-LOG"


def _ok_body(text: str = "真实模型输出", usage: dict[str, int] | None = None) -> dict[str, Any]:
    return {
        "choices": [{"message": {"role": "assistant", "content": text}}],
        "usage": usage or {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19},
    }


def _provider(handler: Any, **overrides: Any) -> OpenAICompatibleProvider:
    opts: dict[str, Any] = {
        "base_url": BASE,
        "api_key": KEY,
        "default_model": "deepseek-chat",
        "transport": httpx.MockTransport(handler),
    }
    opts.update(overrides)
    return OpenAICompatibleProvider(**opts)


def _static(status: int, payload: Any = None):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=payload if payload is not None else {})

    return handler


async def _complete(provider: OpenAICompatibleProvider, **kwargs: Any):
    params: dict[str, Any] = {
        "model": "mock-large",
        "prompt": "请回答：什么是事件溯源？",
        "system": None,
        "temperature": 0.7,
        "max_tokens": 1024,
        "output_mode": "text",
        "output_schema": None,
        "seed": "a" * 64,
        "trace_id": "trace-unit-1",
    }
    params.update(kwargs)
    return await provider.complete(**params)


async def test_text_completion_constructs_request_and_maps_usage():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["headers"] = dict(request.headers)
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_ok_body())

    provider = _provider(handler)
    result = await _complete(provider)
    await provider.aclose()

    assert result.ok is True
    assert result.data == {"text": "真实模型输出", "parsed": None}
    assert result.token_usage == 19
    assert result.extra["usage"] == {"prompt": 12, "completion": 7, "total": 19}
    assert seen["url"] == BASE + "/chat/completions"
    assert seen["headers"]["authorization"] == f"Bearer {KEY}"
    assert seen["headers"]["x-trace-id"] == "trace-unit-1"
    body = seen["body"]
    assert body["model"] == "deepseek-chat" # mock-* 占位名回落
    assert body["messages"] == [{"role": "user", "content": "请回答：什么是事件溯源？"}]
    assert body["temperature"] == 0.7
    assert body["max_tokens"] == 1024
    assert body["stream"] is False
    assert "seed" not in body
    assert "response_format" not in body


async def test_real_model_name_is_preserved():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_ok_body())

    provider = _provider(handler)
    await _complete(provider, model="qwen-plus")
    await provider.aclose()
    assert seen["body"]["model"] == "qwen-plus"


async def test_full_chat_completions_url_is_not_duplicated():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        return httpx.Response(200, json=_ok_body())

    provider = _provider(handler, base_url=BASE + "/chat/completions")
    await _complete(provider)
    await provider.aclose()
    assert seen["path"] == "/v1/chat/completions"


async def test_json_mode_sends_response_format_and_parses():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_ok_body('{"answer": 42}'))

    provider = _provider(handler)
    result = await _complete(
        provider,
        output_mode="json",
        system="你是助手",
        output_schema={"type": "object", "properties": {"answer": {"type": "integer"}}},
    )
    await provider.aclose()

    assert result.ok is True
    assert result.data["parsed"] == {"answer": 42}
    body = seen["body"]
    assert body["response_format"] == {"type": "json_object"}
    assert body["messages"][0]["role"] == "system"
    assert "Return a valid JSON object only" in body["messages"][0]["content"]
    assert '"answer"' in body["messages"][0]["content"]


async def test_json_mode_invalid_json_is_permanent():
    provider = _provider(_static(200, _ok_body("not-json")))
    result = await _complete(provider, output_mode="json")
    await provider.aclose()
    assert result.ok is False
    assert result.error_code == "LLM-RESPONSE-001"
    assert result.transient is False


async def test_send_seed_is_opt_in_and_deterministic():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_ok_body())

    provider = _provider(handler, send_seed=True)
    await _complete(provider, seed="abcdef12" + "0" * 56)
    await provider.aclose()
    assert seen["body"]["seed"] == 0xABCDEF12 % (2**31)


@pytest.mark.parametrize(
    ("status", "code", "transient"),
    [
        (401, "LLM-AUTH-001", False),
        (403, "LLM-AUTH-001", False),
        (429, "LLM-RATE_LIMIT-001", True),
        (400, "LLM-REQUEST-001", False),
        (404, "LLM-REQUEST-001", False),
        (422, "LLM-REQUEST-001", False),
        (500, "LLM-UPSTREAM-001", True),
        (503, "LLM-UPSTREAM-001", True),
    ],
)
async def test_status_classification(status: int, code: str, transient: bool):
    provider = _provider(_static(status))
    result = await _complete(provider)
    await provider.aclose()
    assert result.ok is False
    assert result.error_code == code
    assert result.transient is transient


async def test_timeout_is_transient():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("upstream too slow", request=request)

    provider = _provider(handler)
    result = await _complete(provider)
    await provider.aclose()
    assert result.error_code == "LLM-TIMEOUT-001"
    assert result.transient is True


async def test_network_error_is_transient():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("dns failed", request=request)

    provider = _provider(handler)
    result = await _complete(provider)
    await provider.aclose()
    assert result.error_code == "LLM-UPSTREAM-001"
    assert result.transient is True


async def test_empty_and_oversized_content_are_permanent():
    empty = _provider(_static(200, {"choices": [{"message": {"content": " "}}]}))
    r1 = await _complete(empty)
    await empty.aclose()
    assert r1.error_code == "LLM-RESPONSE-001"
    assert r1.transient is False

    big = _provider(_static(200, _ok_body("x" * 50)), max_response_chars=10)
    r2 = await _complete(big)
    await big.aclose()
    assert r2.error_code == "LLM-RESPONSE-001"


async def test_multimodal_content_parts_are_joined():
    body = {
        "choices": [{"message": {"content": [{"type": "text", "text": "A"}, {"text": "B"}]}}],
        "usage": {},
    }
    provider = _provider(_static(200, body))
    result = await _complete(provider)
    await provider.aclose()
    assert result.ok is True
    assert result.data["text"] == "AB"
    assert result.token_usage > 0 # 上游无 usage 时回落估算


def _unreachable(request: httpx.Request) -> httpx.Response:
    raise AssertionError("未配置时不应发出任何网络请求")


async def test_not_configured_returns_actionable_error_without_network():
    provider = _provider(_unreachable, api_key="", base_url="")
    result = await _complete(provider)
    await provider.aclose()
    assert result.ok is False
    assert result.error_code == "LLM-NOT_CONFIGURED-001"
    assert result.transient is False


async def test_api_key_is_never_logged(caplog):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text=f"upstream echoed secret {KEY}")

    with caplog.at_level(logging.WARNING, logger="app.commands.providers.openai_compatible"):
        provider = _provider(handler)
        result = await _complete(provider)
        await provider.aclose()
    assert result.error_code == "LLM-UPSTREAM-001"
    assert KEY not in caplog.text
    assert "***" in caplog.text


def test_production_guard_rejects_plain_http_endpoint(monkeypatch):
    monkeypatch.setenv("AF_PRODUCTION", "true")
    settings = Settings(
        internal_token="strong-production-token",
        engine_token="distinct-engine-token", # 生产入站凭据须与控制面分离
        llm_base_url="http://internal-llm.local/v1",
        llm_api_key=KEY,
    )
    with pytest.raises(RuntimeError, match="https"):
        _guard_production(settings)


def test_production_guard_allows_https(monkeypatch):
    monkeypatch.setenv("AF_PRODUCTION", "true")
    settings = Settings(
        internal_token="strong-production-token",
        engine_token="distinct-engine-token", # 生产入站凭据须与控制面分离
        llm_base_url="https://api.deepseek.com/v1",
        llm_api_key=KEY,
    )
    _guard_production(settings)


def test_production_guard_requires_distinct_engine_token(monkeypatch):
    """生产模式不允入站凭据回退成控制面共享令牌。"""
    monkeypatch.setenv("AF_PRODUCTION", "true")
    merged = Settings(
        internal_token="strong-production-token",
        engine_token="strong-production-token",
        llm_base_url="https://api.deepseek.com/v1",
    )
    with pytest.raises(RuntimeError, match="AF_ENGINE_INTERNAL_TOKEN"):
        _guard_production(merged)

    weak = Settings(
        internal_token="strong-production-token",
        engine_token="dev-internal-token",
        llm_base_url="https://api.deepseek.com/v1",
    )
    with pytest.raises(RuntimeError, match="AF_ENGINE_INTERNAL_TOKEN"):
        _guard_production(weak)


async def test_end_to_end_execution_uses_real_provider(settings, fake_redis, mem_store, demo_dsl):
    """把真实 provider 注入 dispatcher，验证整条工作流用真实模型输出跑完。"""
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(json.loads(request.content)["model"])
        return httpx.Response(200, json=_ok_body("来自真实模型的答案"))

    real = _provider(handler)

    for node in demo_dsl["nodes"]:
        if node["type"] == "llm":
            node["config"]["provider"] = "openai-compatible"
    dispatcher, _, _ = make_stack(
        settings,
        fake_redis,
        mem_store,
        llm_providers={real.name: real},
    )
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    await dispatcher.submit(req)
    status = await wait_terminal(mem_store, req.execution_id)
    await real.aclose()

    assert status == "SUCCEEDED"
    assert calls == ["deepseek-chat", "deepseek-chat"]


# ---------- 模型清单（画布下拉的数据来源） ----------


async def test_list_models_reads_upstream_catalog() -> None:
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(
            200,
            json={
                "object": "list",
                "data": [
                    {"id": "deepseek-reasoner", "owned_by": "x"},
                    {"id": "deepseek-chat"},
                    {"id": "deepseek-chat"}, # 上游重复项不应重复出现
                ],
            },
        )

    catalog = await _provider(handler).list_models()

    assert catalog.configured is True
    assert catalog.error is None
    assert catalog.models == ["deepseek-chat", "deepseek-reasoner"]
    assert seen["url"] == "https://llm.example.test/v1/models"
    assert seen["auth"] == f"Bearer {KEY}"


async def test_list_models_unconfigured_returns_empty_without_request() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("未配置端点时不应发起请求")

    catalog = await _provider(handler, api_key="").list_models()

    assert catalog.configured is False
    assert catalog.models == []
    assert catalog.error is None


async def test_list_models_rejects_without_leaking_upstream_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": f"bad key {KEY}"}})

    catalog = await _provider(handler).list_models()

    assert catalog.configured is True
    assert catalog.models == []
    assert catalog.error == "LLM-AUTH-001"


async def test_list_models_handles_non_json_body() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>not json</html>")

    catalog = await _provider(handler).list_models()

    assert catalog.configured is True
    assert catalog.models == []
    assert catalog.error == "LLM-RESPONSE-001"


async def test_list_models_transport_failure_is_reported_not_raised() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom", request=request)

    catalog = await _provider(handler).list_models()

    assert catalog.configured is True
    assert catalog.models == []
    assert catalog.error == "LLM-UPSTREAM-001"


# ---------- 流式（OutputDelta 展示层） ----------


def _sse_body(*events: dict[str, Any]) -> bytes:
    """拼出 OpenAI 兼容的 SSE 流响应体。"""
    chunks = [f"data: {json.dumps(e, ensure_ascii=False)}\n\n".encode() for e in events]
    chunks.append(b"data: [DONE]\n\n")
    return b"".join(chunks)


def _delta(content: str) -> dict[str, Any]:
    return {"choices": [{"delta": {"content": content}}]}


async def test_stream_enabled_sends_stream_flag_and_assembles_deltas():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(
            200,
            content=_sse_body(_delta("事件"), _delta("溯源"), _delta("是"), _delta("好文明")),
            headers={"Content-Type": "text/event-stream"},
        )

    provider = _provider(handler, stream=True)
    result = await _complete(provider)
    await provider.aclose()

    assert seen["body"]["stream"] is True
    assert result.ok is True
    # 完整结果与非流式路径完全一致
    assert result.data["text"] == "事件溯源是好文明"
    # 增量作为展示层事件单独携带，供节点在 COMMAND_COMPLETED 前逐个推送
    assert result.extra["deltas"] == ["事件", "溯源", "是", "好文明"]


async def test_stream_disabled_by_default_is_non_streaming():
    seen: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, json=_ok_body())

    provider = _provider(handler) # 未传 stream=True，默认关闭
    result = await _complete(provider)
    await provider.aclose()

    assert seen["body"]["stream"] is False
    assert result.ok is True
    assert "deltas" not in result.extra


async def test_stream_single_delta_is_still_reported():
    """即使只切出一条增量也要如实返回：上游切分粒度不可控，
    用条数去「优化」展示层会造成打字机时灵时不灵的间歇性 bug。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=_sse_body(_delta("一锤定音")))

    provider = _provider(handler, stream=True)
    result = await _complete(provider)
    await provider.aclose()

    assert result.ok is True
    assert result.data["text"] == "一锤定音"
    assert result.extra["deltas"] == ["一锤定音"]


async def test_stream_skips_reasoning_and_heartbeat_lines():
    def handler(request: httpx.Request) -> httpx.Response:
        reasoning = json.dumps(
            {"choices": [{"delta": {"reasoning_content": "thinking"}}]}, ensure_ascii=False
        ).encode()
        delta = json.dumps(_delta("正文"), ensure_ascii=False).encode()
        chunks = [
            b"data: " + reasoning + b"\n\n",
            b": heartbeat\n\n",
            b"data: " + delta + b"\n\n",
            b"data: not-json\n\n",
            b"data: [DONE]\n\n",
        ]
        return httpx.Response(200, content=b"".join(chunks))

    provider = _provider(handler, stream=True)
    result = await _complete(provider)
    await provider.aclose()

    assert result.ok is True
    assert result.data["text"] == "正文"
    assert result.extra["deltas"] == ["正文"]


async def test_stream_empty_result_is_permanent_error():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"data: [DONE]\n\n")

    provider = _provider(handler, stream=True)
    result = await _complete(provider)
    await provider.aclose()

    assert result.ok is False
    assert result.error_code == "LLM-RESPONSE-001"
    assert result.transient is False


async def test_stream_prefers_upstream_usage_when_present():
    """SSE 末帧携带 usage 时必须用真实值，而非本地字符估算。"""
    usage_frame = {
        "choices": [{"delta": {}}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
    }

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            content=_sse_body(_delta("答"), _delta("案"), usage_frame),
            headers={"Content-Type": "text/event-stream"},
        )

    provider = _provider(handler, stream=True)
    result = await _complete(provider)
    await provider.aclose()

    assert result.ok is True
    assert result.token_usage == 15
    assert result.extra["usage"]["prompt"] == 10
    assert result.extra["usage"]["completion"] == 5
    assert result.extra["usage"]["total"] == 15
