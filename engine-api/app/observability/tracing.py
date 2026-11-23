"""LangFuse OTLP 导出（可选）。复用 DocQA 自托管实例，按 project 隔离（pk/sk 区分）。

开关关闭时不安装任何 OTel 组件：span 助手退化为 no-op，执行管道行为不变。
脱敏红线与日志一致：span 只带 id/计数/model/outcome，绝不带提示词/回答/工具入参原文；
因此 start_as_current_span 一律 record_exception=False（异常 message 可能含用户文本），
失败状态只记错误码。
"""

from __future__ import annotations

import base64
import secrets
from collections.abc import Iterator
from contextlib import contextmanager
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

if TYPE_CHECKING:
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import SpanExporter

    from ..config import Settings

_provider: TracerProvider | None = None

_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})


class _NoopSpan:
    def set_attribute(self, key: str, value: Any) -> None:
        pass


def _validate(settings: Settings) -> None:
    """启用即 fail-fast（照 _guard_production 写法）：缺凭据/远端未 opt-in 拒绝启动。"""
    if not settings.langfuse_public_key or not settings.langfuse_secret_key:
        raise RuntimeError(
            "FAIL-FAST: AF_LANGFUSE_PUBLIC_KEY / AF_LANGFUSE_SECRET_KEY must be set "
            "when LangFuse tracing is enabled."
        )
    host = urlsplit(settings.langfuse_otlp_endpoint).hostname or ""
    if host not in _LOOPBACK_HOSTS and not settings.langfuse_allow_private:
        raise RuntimeError(
            "FAIL-FAST: AF_LANGFUSE_OTLP_ENDPOINT is not loopback. "
            "Set AF_LANGFUSE_ALLOW_PRIVATE=true only when the remote endpoint is trusted."
        )


def setup_tracing(
    settings: Settings, *, exporter: SpanExporter | None = None
) -> TracerProvider | None:
    """构建独立 TracerProvider 并挂 OTLP HTTP 导出；关闭时返回 None。

    exporter 覆盖位仅供测试注入内存替身；生产路径恒走 OTLPSpanExporter。
    不设全局 tracer provider：导出只影响本模块发出的 span，不碰既有管道。
    """
    global _provider
    if not settings.langfuse_tracing_enabled:
        return None
    _validate(settings)

    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    from opentelemetry.sdk.trace.sampling import ALWAYS_ON, ParentBased

    if exporter is None:
        credential = base64.b64encode(
            f"{settings.langfuse_public_key}:{settings.langfuse_secret_key}".encode()
        ).decode()
        exporter = OTLPSpanExporter(
            endpoint=settings.langfuse_otlp_endpoint,
            headers={"Authorization": f"Basic {credential}"},
        )
    provider = TracerProvider(
        sampler=ParentBased(ALWAYS_ON),
        resource=Resource.create({"service.name": "agentflow-engine"}),
    )
    provider.add_span_processor(BatchSpanProcessor(exporter))
    _provider = provider
    return provider


def shutdown_tracing(provider: TracerProvider | None) -> None:
    global _provider
    if provider is None:
        return
    provider.force_flush()
    provider.shutdown()
    if _provider is provider:
        _provider = None


@contextmanager
def execution_span(trace_id: str, attributes: dict[str, Any]) -> Iterator[Any]:
    """执行根 span。以透传的 W3C traceId 为远端父上下文 ⇒ LangFuse 里的 traceId
    与 Java/前端看到的 traceId 是同一个，追踪链接才能直接对上。"""
    if _provider is None:
        yield _NoopSpan()
        return

    from opentelemetry import trace as otel_trace
    from opentelemetry.trace import NonRecordingSpan, SpanContext, TraceFlags

    # trace_id 入参未经 hex 校验：非法值退回随机 traceId，导出绝不能拖垮执行本身
    try:
        parent_trace_id = int(trace_id, 16)
        if not 0 < parent_trace_id < 1 << 128:
            raise ValueError
    except ValueError:
        parent_trace_id = secrets.randbits(128) or 1
    parent = SpanContext(
        trace_id=parent_trace_id,
        span_id=secrets.randbits(64) or 1,
        is_remote=True,
        trace_flags=TraceFlags(TraceFlags.SAMPLED),
    )
    ctx = otel_trace.set_span_in_context(NonRecordingSpan(parent))
    tracer = _provider.get_tracer("agentflow.engine")
    with tracer.start_as_current_span(
        "workflow", context=ctx, attributes=attributes, record_exception=False
    ) as span:
        yield span


@contextmanager
def node_span(name: str, attributes: dict[str, Any]) -> Iterator[Any]:
    """节点 span：挂在当前执行根 span 下（asyncio 子任务继承 contextvars）。"""
    if _provider is None:
        yield _NoopSpan()
        return
    tracer = _provider.get_tracer("agentflow.engine")
    with tracer.start_as_current_span(name, attributes=attributes, record_exception=False) as span:
        yield span
