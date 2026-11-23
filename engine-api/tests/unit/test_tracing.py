"""LangFuse OTLP 导出：开关守卫、exporter 接线、traceId 对齐、span 脱敏。

不依赖真实 LangFuse：导出 wiring 用 OTLPSpanExporter 的构造参数断言，
span 内容用 SDK 自带的 InMemorySpanExporter 替身收集。
"""

from __future__ import annotations

import base64

import pytest
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from app.config import Settings
from app.engine.requests import ExecuteRequest
from app.observability import tracing

from .conftest import make_execute_request, make_stack
from .test_scheduler import wait_terminal

# span 只允许 id/计数/model/outcome 这几类键；出现任何载荷键即脱敏破防
_ALLOWED_ATTR_KEYS = frozenset(
    {
        "execution_id",
        "definition_id",
        "version_id",
        "node_key",
        "node_type",
        "model",
        "status",
        "outcome",
        "attempts",
        "token_used",
        "steps_used",
        "latency_ms",
    }
)


@pytest.fixture(autouse=True)
def _reset_tracing():
    yield
    tracing.shutdown_tracing(tracing._provider)


def _enabled_settings(**overrides) -> Settings:
    return Settings(
        internal_token="test-token",
        engine_token="test-token",
        langfuse_tracing_enabled=True,
        langfuse_public_key="pk-lf-test",
        langfuse_secret_key="sk-lf-test",
        **overrides,
    )


def test_disabled_installs_nothing(settings):
    assert tracing.setup_tracing(settings) is None
    assert tracing._provider is None
    # no-op span：执行管道在关闭时照跑，set_attribute 不产生任何副作用
    with tracing.execution_span("a" * 32, {"execution_id": "e"}) as span:
        span.set_attribute("status", "SUCCEEDED")
    with tracing.node_span("llm draft", {"node_key": "draft"}) as span:
        span.set_attribute("outcome", "SUCCEEDED")


def test_enabled_wires_otlp_http_exporter_with_basic_auth():
    settings = _enabled_settings()
    provider = tracing.setup_tracing(settings)
    assert provider is not None
    processors = provider._active_span_processor._span_processors
    assert len(processors) == 1
    processor = processors[0]
    assert isinstance(processor, BatchSpanProcessor)
    exporter = processor.span_exporter
    assert exporter._endpoint == "http://127.0.0.1:13000/api/public/otel/v1/traces"
    expected = base64.b64encode(b"pk-lf-test:sk-lf-test").decode()
    assert exporter._client._headers["authorization"] == f"Basic {expected}"


def test_enabled_without_keys_fails_fast():
    settings = Settings(
        internal_token="test-token", engine_token="test-token", langfuse_tracing_enabled=True
    )
    with pytest.raises(RuntimeError, match="AF_LANGFUSE_PUBLIC_KEY"):
        tracing.setup_tracing(settings)


def test_non_loopback_endpoint_requires_explicit_opt_in():
    settings = _enabled_settings(
        langfuse_otlp_endpoint="http://10.0.0.5:13000/api/public/otel/v1/traces"
    )
    with pytest.raises(RuntimeError, match="AF_LANGFUSE_ALLOW_PRIVATE"):
        tracing.setup_tracing(settings)
    provider = tracing.setup_tracing(
        _enabled_settings(
            langfuse_otlp_endpoint="http://10.0.0.5:13000/api/public/otel/v1/traces",
            langfuse_allow_private=True,
        )
    )
    assert provider is not None


def test_malformed_trace_id_falls_back_without_crash():
    exporter = InMemorySpanExporter()
    provider = tracing.setup_tracing(_enabled_settings(), exporter=exporter)
    with tracing.execution_span("not-a-hex-id", {"execution_id": "e"}) as span:
        span.set_attribute("status", "SUCCEEDED")
    tracing.shutdown_tracing(provider)
    spans = exporter.get_finished_spans()
    assert len(spans) == 1
    assert spans[0].context.trace_id > 0


async def test_spans_align_trace_id_and_carry_no_payload_text(fake_redis, mem_store, demo_dsl):
    exporter = InMemorySpanExporter()
    provider = tracing.setup_tracing(_enabled_settings(), exporter=exporter)
    dispatcher, _, _ = make_stack(_enabled_settings(), fake_redis, mem_store)
    req = ExecuteRequest.model_validate(make_execute_request(demo_dsl))
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"
    tracing.shutdown_tracing(provider)

    spans = exporter.get_finished_spans()
    # 1 个执行根 span + 5 个节点 span
    assert len(spans) == 6
    root = next(s for s in spans if s.name == "workflow")
    assert root.parent is not None and root.parent.is_remote
    for span in spans:
        # LangFuse 侧的 traceId 必须与 Java/前端透传的 traceId 完全一致
        assert span.context.trace_id == int(req.trace_id, 16)
        if span is not root:
            assert span.parent.span_id == root.context.span_id
        assert set(span.attributes) <= _ALLOWED_ATTR_KEYS
        for value in span.attributes.values():
            text = str(value)
            # 问题原文 / 提示词模板 / 模型回答 / 工具入参一律不得出现
            assert "如何保证恰好一次" not in text
            assert "请根据问题起草答案要点" not in text
            assert "确定性测试输出" not in text
            assert "输入摘要" not in text

    llm_spans = [s for s in spans if s.attributes.get("node_type") == "llm"]
    assert len(llm_spans) == 2
    for span in llm_spans:
        assert span.attributes["model"] == "mock-large"
        assert span.attributes["outcome"] == "SUCCEEDED"
        assert span.attributes["attempts"] == 1
    assert root.attributes["status"] == "SUCCEEDED"
    assert root.attributes["steps_used"] == 5


async def test_failed_node_span_records_error_code_only(fake_redis, mem_store, demo_dsl):
    exporter = InMemorySpanExporter()
    provider = tracing.setup_tracing(_enabled_settings(), exporter=exporter)
    dispatcher, _, _ = make_stack(_enabled_settings(), fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(
            demo_dsl, policy={"tokenBudget": 1, "maxSteps": 30, "deadlineMs": 60000}
        )
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "FAILED"
    tracing.shutdown_tracing(provider)

    spans = exporter.get_finished_spans()
    failed = [s for s in spans if s.attributes.get("outcome") == "EXEC-BUDGET_EXHAUSTED"]
    assert len(failed) == 1
    for span in spans:
        assert set(span.attributes) <= _ALLOWED_ATTR_KEYS
        for value in span.attributes.values():
            # 错误 message 含节点上下文文本，只允许错误码进 span
            assert "exhausted before node" not in str(value)
