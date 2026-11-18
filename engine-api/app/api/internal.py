"""内部 API（/internal/**）：仅 gateway 可达（网络策略 + X-Internal-Token）。"""

from __future__ import annotations

import uuid
from typing import Any, Literal

from fastapi import APIRouter, Query, Request
from fastapi.responses import Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from ..config import Settings
from ..dsl.models import CamelModel
from ..dsl.validate import ValidationResult, validate_dsl
from ..engine.replay import rebuild_state
from ..engine.requests import ExecuteRequest
from ..errors import EngineError
from ..events.store import EventStore

router = APIRouter(prefix="/internal")


class ValidateRequest(CamelModel):
    dsl: dict[str, Any]


class GateResolveRequest(CamelModel):
    decision: Literal["APPROVED", "REJECTED", "TIMEOUT"]
    resolved_by: str = ""
    comment: str | None = None


class LlmModelsResponse(CamelModel):
    """可用模型清单。未配置真实端点时 configured=false，前端据此退回手填。"""

    configured: bool
    default_model: str
    models: list[str]
    error: str | None = None


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _store(request: Request) -> EventStore:
    return request.app.state.store


@router.post("/dsl/validate")
async def validate(body: ValidateRequest, request: Request) -> ValidationResult:
    return validate_dsl(body.dsl, _settings(request))


@router.get("/llm/models")
async def llm_models(request: Request) -> LlmModelsResponse:
    """模型清单只对已配置的端点发起，密钥与 base_url 不出现在响应里。"""
    settings = _settings(request)
    catalog = await request.app.state.llm_provider.list_models()
    return LlmModelsResponse(
        configured=catalog.configured,
        default_model=settings.llm_model if catalog.configured else "",
        models=catalog.models,
        error=catalog.error,
    )


@router.post("/executions", status_code=202)
async def execute(body: ExecuteRequest, request: Request) -> dict[str, Any]:
    return await request.app.state.dispatcher.submit(body)


@router.post("/executions/{execution_id}/gates/{node_key}/resolve")
async def resolve_gate(
    execution_id: uuid.UUID, node_key: str, body: GateResolveRequest, request: Request
) -> dict[str, Any]:
    """人工审批回调（P3）。幂等：重复裁决返回 ALREADY_RESOLVED。"""
    result = await request.app.state.dispatcher.resolve_gate(
        execution_id, node_key, body.decision, body.resolved_by, body.comment
    )
    status = result.get("status")
    if status in ("NOT_FOUND", "NOT_SUSPENDED"):
        raise EngineError("WF-NOT_FOUND-001", "gate not suspended")
    if status == "INVALID_DECISION":
        raise EngineError("DSL-VALIDATION-001", "invalid decision")
    if status == "BUSY":
        raise EngineError("EXEC-GATE_BUSY-001", "gate is being resolved, retry")
    return result


@router.post("/executions/{execution_id}/cancel")
async def cancel(execution_id: uuid.UUID, request: Request) -> dict[str, Any]:
    return await request.app.state.dispatcher.cancel(execution_id)


@router.get("/executions/{execution_id}/events")
async def execution_events(
    execution_id: uuid.UUID,
    request: Request,
    after_seq: int = Query(0, ge=0),
    after_seq_camel: int | None = Query(None, alias="afterSeq", ge=0),
    limit: int = Query(500, ge=1),
) -> dict[str, Any]:
    settings = _settings(request)
    limit = min(limit, settings.event_tail_max_limit)
    after = after_seq_camel if after_seq_camel is not None else after_seq
    events = await _store(request).tail_for_execution(execution_id, after, limit)
    return {
        "events": [e.to_dict() for e in events],
        "lastSeq": events[-1].seq if events else after,
    }


@router.get("/events")
async def tail_events(
    request: Request,
    after_seq: int = Query(0, ge=0),
    after_seq_camel: int | None = Query(None, alias="afterSeq", ge=0),
    limit: int = Query(500, ge=1),
) -> dict[str, Any]:
    limit = min(limit, _settings(request).event_tail_max_limit)
    after = after_seq_camel if after_seq_camel is not None else after_seq
    events = await _store(request).tail(after, limit)
    return {
        "events": [e.to_dict() for e in events],
        "lastSeq": events[-1].seq if events else after,
    }


@router.get("/executions/{execution_id}/io/{node_key}")
async def node_io(execution_id: uuid.UUID, node_key: str, request: Request) -> dict[str, Any]:
    io = await _store(request).get_node_io(execution_id, node_key)
    if io["in"] is None and io["out"] is None:
        raise EngineError("WF-NOT_FOUND-001", "node io not found")
    return io


@router.get("/executions/{execution_id}/state")
async def execution_state(execution_id: uuid.UUID, request: Request) -> dict[str, Any]:
    events = await _store(request).read_all(execution_id)
    if not events:
        raise EngineError("WF-NOT_FOUND-001", "execution not found")
    state = rebuild_state(execution_id, events)
    return {
        "executionId": str(execution_id),
        "status": state.status,
        "tokenUsed": state.token_used,
        "stepsUsed": state.steps_used,
        "errorCode": state.error_code,
        "result": state.result,
        "nodes": {
            k: {
                "status": n.status,
                "attempt": n.attempt,
                "errorCode": n.error_code,
                "tokenUsed": n.token_used,
            }
            for k, n in state.nodes.items()
        },
    }


@router.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@router.get("/metrics")
async def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
