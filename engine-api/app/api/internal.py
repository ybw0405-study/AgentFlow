"""内部 API（/internal/**）：仅 gateway 可达（网络策略 + X-Internal-Token）。"""

from __future__ import annotations

import asyncio
import uuid
from typing import Any, Literal

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import Field

from ..config import Settings
from ..dsl.models import CamelModel
from ..dsl.validate import ValidationResult, validate_dsl
from ..engine.replay import rebuild_state_hydrated
from ..engine.requests import ExecuteRequest, PolicyIn
from ..errors import EngineError
from ..events.store import EventStore
from ..events.types import EventType
from ..preview import plan_preview

router = APIRouter(prefix="/internal")


class ValidateRequest(CamelModel):
    dsl: dict[str, Any]


class GateResolveRequest(CamelModel):
    decision: Literal["APPROVED", "REJECTED", "TIMEOUT"]
    resolved_by: str = ""
    comment: str | None = None


class LlmModelItem(CamelModel):
    """单个模型的能力标注。字段拿不到就缺省，前端不显示空标签。"""

    id: str
    tags: list[str] = Field(default_factory=list)
    context_window: int | None = None
    max_output_tokens: int | None = None
    source: Literal["upstream", "inferred"] = "inferred"


class LlmModelsResponse(CamelModel):
    """可用模型清单。未配置真实端点时 configured=false，前端据此退回手填。"""

    configured: bool
    default_model: str
    models: list[str]
    error: str | None = None
    items: list[LlmModelItem] = Field(default_factory=list)


def _settings(request: Request) -> Settings:
    return request.app.state.settings


def _store(request: Request) -> EventStore:
    return request.app.state.store


@router.post("/dsl/validate")
async def validate(body: ValidateRequest, request: Request) -> ValidationResult:
    return validate_dsl(body.dsl, _settings(request))


@router.get("/llm/models")
async def llm_models(request: Request) -> LlmModelsResponse:
    """模型清单只对已配置的端点发起，密钥与 base_url 不出现在响应里。

    models 是纯 id 列表（下拉框用），items 带能力标注（推理型、长上下文、输出上限），
    后者只用于界面提示，不参与任何执行决策。
    """
    settings = _settings(request)
    catalog = await request.app.state.llm_provider.list_models()
    return LlmModelsResponse(
        configured=catalog.configured,
        default_model=settings.llm_model if catalog.configured else "",
        models=catalog.models,
        error=catalog.error,
        items=[
            LlmModelItem(
                id=facts.id,
                tags=list(facts.tags),
                context_window=facts.context_window,
                max_output_tokens=facts.max_output_tokens,
                source=facts.source,
            )
            for facts in catalog.items
        ],
    )


@router.post("/executions", status_code=202)
async def execute(body: ExecuteRequest, request: Request) -> dict[str, Any]:
    result = await request.app.state.dispatcher.submit(body)
    # 带 note 的结果表示「引擎接受但没派发」（租约被占 / 关停中）。
    # 回 202 会让控制面插入 PENDING 并记 EXECUTION_TRIGGERED，而引擎侧根本
    # 没有这条执行 —— 必须给明确错误码，让调用方把假 PENDING 收口。
    note = result.get("note")
    if isinstance(note, str) and note:
        raise EngineError("EXEC-DISPATCH_REJECTED-001", f"engine did not dispatch: {note}")
    return result


class NodePreviewRequest(CamelModel):
    """单节点试跑：整份草稿 + 目标节点 + 本次要用的变量。

    传整份草稿而不是「节点配置」：试跑要把上游真实跑一遍，模板里引用的
    `nodes.xxx.output` 才有值，否则用户拿到的只是一个渲染失败。
    """

    dsl: dict[str, Any]
    node_key: str
    variables: dict[str, Any] = Field(default_factory=dict)
    tenant_id: str = "preview"
    space_id: str = "preview"
    requested_by: str = "preview"
    authorized_connector_ids: list[str] = Field(default_factory=list)
    token_budget: int | None = None
    timeout_ms: int = 120_000


def _last_event(events: list[Any], node_key: str, event_type: EventType) -> dict[str, Any] | None:
    for event in reversed(events):
        if event.node_key == node_key and event.event_type == event_type:
            return dict(event.payload or {})
    return None


@router.post("/nodes/preview")
async def preview_node(body: NodePreviewRequest, request: Request) -> dict[str, Any]:
    """试跑到目标节点：裁剪上游 + 真实执行 + 回传该节点的输入输出与链路。

    与整体执行同源（同一个调度器），因此结果、错误码、token 计数都可以直接采信。
    """
    settings = _settings(request)
    plan = plan_preview(body.dsl, body.node_key)
    checked = validate_dsl(plan.dsl, settings)
    if not checked.ok:
        detail = "；".join(f"{e.code}: {e.message}" for e in checked.errors[:3])
        raise EngineError("DSL-VALIDATION-001", detail or "preview dsl invalid")

    execution_id = uuid.uuid4()
    dispatcher = request.app.state.dispatcher
    # 试跑无论正常结束、超时还是抛异常，都必须强制落终态并清掉 spec。
    # 否则 execution_tracker 会留一条 OPEN，恢复扫描（启动扫描 + 周期 sweep）
    # 会在引擎重启后把它当「崩溃的业务执行」续跑，重复调用模型并重复计费。
    dispatched = False
    try:
        submitted = await dispatcher.submit(
            ExecuteRequest(
                execution_id=execution_id,
                definition_id=f"preview:{body.node_key}",
                version_id="preview",
                tenant_id=body.tenant_id,
                space_id=body.space_id,
                triggered_by=body.requested_by,
                idempotency_key=f"preview-{execution_id}",
                version_snapshot=plan.dsl,
                authorized_connector_ids=body.authorized_connector_ids,
                initial_variables=body.variables,
                policy=PolicyIn(
                    token_budget=body.token_budget,
                    max_steps=60,
                    deadline_ms=body.timeout_ms,
                ),
                trace_id=f"preview-{execution_id.hex[:12]}",
                preview=True,
            )
        )
        note = submitted.get("note")
        if isinstance(note, str) and note:
            raise EngineError("EXEC-DISPATCH_REJECTED-001", f"preview was not dispatched: {note}")
        # 只有真正落库并进入调度才算 dispatched；被拒收的执行没有任何
        # spec/事件，finalize_preview 会凭空造出一条 CANCELLED + TERMINAL tracker，
        # 属孤儿数据。未 dispatched 时跳过 finalize。
        dispatched = True
        finished = await dispatcher.wait(execution_id, body.timeout_ms / 1000)

        store = _store(request)
        events = await store.read_all(execution_id)
        state = await rebuild_state_hydrated(execution_id, events, store)

        target = state.nodes.get(body.node_key)
        completed = _last_event(events, body.node_key, EventType.NODE_COMPLETED)
        failed = _last_event(events, body.node_key, EventType.NODE_FAILED)
        io = await store.get_node_io(execution_id, body.node_key)

        trace = []
        for key in (*plan.upstream, plan.target):
            runtime = state.nodes.get(key)
            node_completed = _last_event(events, key, EventType.NODE_COMPLETED)
            node_failed = _last_event(events, key, EventType.NODE_FAILED)
            trace.append(
                {
                    "key": key,
                    "status": runtime.status if runtime else "PENDING",
                    "tokenUsed": runtime.token_used if runtime else 0,
                    "latencyMs": (node_completed or node_failed or {}).get("latencyMs"),
                    "errorCode": (runtime.error_code if runtime else None)
                    or (node_failed or {}).get("errorCode"),
                }
            )

        output = target.output if target and target.output else None
        error_code = (failed or {}).get("errorCode") or (target.error_code if target else None)
        error_message = (failed or {}).get("message")
        status = state.status
        if not finished and status not in ("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"):
            status = "TIMED_OUT"
            error_code = error_code or "PREVIEW-TIMEOUT-001"
            error_message = error_message or f"试跑在 {body.timeout_ms}ms 内没有结束，已请求取消"
        if finished and target is not None and target.status == "SKIPPED":
            error_code = error_code or "PREVIEW-SKIPPED-001"
            error_message = error_message or "上游条件分支没有命中这个节点，本次试跑未执行它"

        return {
            "executionId": str(execution_id),
            "nodeKey": body.node_key,
            "status": status,
            "nodeStatus": target.status if target else "PENDING",
            "output": output,
            "input": (io.get("in") or {}).get("payload"),
            "errorCode": error_code,
            "errorMessage": error_message,
            "tokenUsed": target.token_used if target else 0,
            "latencyMs": (completed or failed or {}).get("latencyMs"),
            "totalTokens": state.token_used,
            "result": state.result,
            "trace": trace,
        }
    finally:
        if dispatched:
            await dispatcher.finalize_preview(execution_id)


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


@router.get("/mq/status")
async def mq_status(request: Request) -> dict[str, Any]:
    """裁决 MQ 通道水位：连接状态、已消费/已应用/重复计数与最近错误。

    没有它就只能靠日志猜「MQ 到底有没有在工作」；e2e 也用它确认消费端真的连上了。
    """
    consumer = getattr(request.app.state, "gate_consumer", None)
    if consumer is None:
        return {"enabled": False, "reason": "AF_MQ_ENABLED / AF_MQ_CONSUME 未开启"}
    return consumer.status()


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
    space_id: str | None = Query(None, alias="spaceId"),
) -> dict[str, Any]:
    """事件尾读。调用方带 spaceId 时，过滤条件下推到引擎 SQL 层，
    不再无条件返回全租户事件。空/空白视为未指定（系统级投影器走全局路径）。
    """
    limit = min(limit, _settings(request).event_tail_max_limit)
    after = after_seq_camel if after_seq_camel is not None else after_seq
    scope = space_id.strip() if space_id and space_id.strip() else None
    events = await _store(request).tail(after, limit, space_id=scope)
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
    state = await rebuild_state_hydrated(execution_id, events, _store(request))
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
    """兼容别名（liveness）：进程存活即 200，README/既有探针口径不变。

    readiness 请用 /health/ready —— 它会真探 PG + Redis，依赖 down 返回 503。
    """
    return {"status": "ok"}


@router.get("/health/live")
async def health_live() -> dict[str, str]:
    """liveness：恒活，绝不因依赖抖动让编排器把存活实例摘除。"""
    return {"status": "ok"}


@router.get("/health/ready")
async def health_ready(request: Request) -> Response:
    """readiness：短超时 SELECT 1 探 PG + Redis PING，任一 down 返回 503。

    与 Java 侧 HealthController 的 UP/DEGRADED 口径对齐；每个依赖独立探测，
    单次超时由 settings.health_probe_timeout_s 约束，不会拖住探针调用方。
    """
    timeout = _settings(request).health_probe_timeout_s
    checks: dict[str, str] = {}

    async def probe(name: str, coro: Any) -> None:
        try:
            await asyncio.wait_for(coro, timeout=timeout)
            checks[name] = "UP"
        except Exception:
            checks[name] = "DOWN"

    await probe("postgres", _store(request).ping())
    await probe("redis", request.app.state.redis.ping())

    ok = all(v == "UP" for v in checks.values())
    return JSONResponse(
        status_code=200 if ok else 503,
        content={"status": "UP" if ok else "DEGRADED", "checks": checks},
    )


@router.get("/metrics")
async def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
