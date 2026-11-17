"""trace 透传：W3C traceparent / X-Trace-Id，与 Java 侧 TraceIdFilter 对齐。"""

from __future__ import annotations

import re
import uuid
from contextvars import ContextVar

_TRACEPARENT = re.compile(r"^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
_TRACE_ID = re.compile(r"^[0-9a-f]{32}$")

current_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def extract_trace_id(headers: dict[str, str]) -> str:
    tp = headers.get("traceparent", "")
    m = _TRACEPARENT.match(tp)
    if m and m.group(1) != "0" * 32:
        return m.group(1)
    xt = headers.get("x-trace-id", "")
    if _TRACE_ID.match(xt) and xt != "0" * 32:
        return xt
    return uuid.uuid4().hex


def new_traceparent(trace_id: str) -> str:
    return f"00-{trace_id}-{uuid.uuid4().hex[:16]}-01"
