"""ProblemDetail 响应（与 Java 侧 ProblemDetailFactory 同构：code + traceId 扩展属性）。"""

from __future__ import annotations

from fastapi.responses import JSONResponse

from ..observability.trace import current_trace_id

_TYPE_BASE = "https://agentflow.example/problems/"


def problem(
    status: int,
    code: str,
    title: str,
    detail: str | None = None,
    errors: list | None = None,
) -> JSONResponse:
    body: dict = {
        "type": _TYPE_BASE + code.lower().replace("_", "-"),
        "title": title,
        "status": status,
        "code": code,
        "traceId": current_trace_id.get("") or None,
    }
    if detail:
        body["detail"] = detail[:1000]
    if errors:
        body["errors"] = errors[:20]
    return JSONResponse(status_code=status, content=body, media_type="application/problem+json")
