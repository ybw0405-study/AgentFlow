"""engine-api 应用装配。"""

from __future__ import annotations

import asyncio
import hmac
import logging
import sys
from contextlib import asynccontextmanager

if sys.platform == "win32":
    # Windows 上 asyncio 默认 ProactorEventLoop；asyncpg/部分异步驱动在
    # SelectorEventLoop 下更稳（与 DocQA 一致）。须在 uvicorn 建循环前设置。
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

import redis.asyncio as aioredis
from fastapi import FastAPI, Request
from pydantic import ValidationError

from . import __version__
from .api.internal import router as internal_router
from .api.problems import problem
from .commands.providers.openai_compatible import OpenAICompatibleProvider
from .config import Settings, get_settings
from .db.migrate import run_migrations
from .engine.dispatcher import Dispatcher
from .errors import EngineError
from .events.log import EventLog
from .events.publisher import EventPublisher
from .events.store import EventStore
from .governance.budget import BudgetLedger
from .messaging import GateDecisionConsumer
from .nodes import build_executors
from .observability.logging import setup_logging
from .observability.trace import current_trace_id, extract_trace_id

logger = logging.getLogger(__name__)

_ERROR_STATUS = {
    "WF-NOT_FOUND-001": 404,
    "WF-FORBIDDEN-001": 403,
    "DSL-VALIDATION-001": 422,
    "DSL-CYCLE-001": 422,
    "DSL-REF-001": 422,
    "DSL-ORPHAN-001": 422,
    "DSL-BRANCH-001": 422,
    "EXEC-GATE_BUSY-001": 409,
}


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    from .config import _guard_production

    _guard_production(settings)
    setup_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        redis = aioredis.from_url(settings.redis_url, decode_responses=True)
        store = EventStore(settings.pg_dsn, payload_max_bytes=settings.event_payload_max_bytes)
        await store.connect()
        await run_migrations(store.pool, settings.pg_migrations_path)
        event_log = EventLog(store, EventPublisher(redis))
        budget = BudgetLedger(redis)
        from .nodes.subworkflow_resolver import GatewaySubworkflowResolver

        subworkflow_resolver = GatewaySubworkflowResolver(
            settings.gateway_base_url, settings.internal_token
        )
        # 真实模型 provider 始终注册：未配置 AF_LLM_* 时调用会返回明确的
        # LLM-NOT_CONFIGURED-001，而不是笼统的 unknown provider。
        real_llm = OpenAICompatibleProvider.from_settings(settings)
        llm_providers = {real_llm.name: real_llm}
        executors = build_executors(
            redis,
            llm_providers,
            subworkflow_resolver,
            connector_gateway=(settings.gateway_base_url, settings.internal_token),
            knowledge_gateway=(settings.gateway_base_url, settings.internal_token),
        )
        dispatcher = Dispatcher(settings, store, event_log, redis, executors, budget)
        app.state.settings = settings
        app.state.redis = redis
        app.state.store = store
        app.state.event_log = event_log
        app.state.dispatcher = dispatcher
        app.state.llm_provider = real_llm  # /internal/llm/models 读模型清单
        await dispatcher.recover_on_startup()  # D4：崩溃恢复扫描
        dispatcher.start_sweep()  # 周期兜底
        dispatcher.start_gate_sweeper()  # human_gate 超时自动裁决
        # 裁决的 MQ 通道：与控制面 HTTP 回调并行，用于引擎「被隔离/控制面重启中」时仍能续跑
        gate_consumer: GateDecisionConsumer | None = None
        if settings.mq_enabled and settings.mq_consume:
            gate_consumer = GateDecisionConsumer(
                dispatcher,
                namesrv=settings.mq_namesrv,
                topic=settings.mq_gate_topic,
                group=settings.mq_gate_group,
                poll_timeout_ms=settings.mq_poll_timeout_ms,
                batch=settings.mq_pull_batch,
            )
            gate_consumer.start()
        app.state.gate_consumer = gate_consumer
        try:
            yield
        finally:
            if gate_consumer is not None:
                # stop() 会 join 拉取线程，放线程里做，避免阻塞事件循环的收尾
                await asyncio.to_thread(gate_consumer.stop)
            if dispatcher._sweep_task is not None:
                dispatcher._sweep_task.cancel()
            if dispatcher._gate_task is not None:
                dispatcher._gate_task.cancel()
            await subworkflow_resolver.aclose()
            # 释放 tool 节点持有的 httpx.AsyncClient（否则每次执行泄漏连接句柄）
            for executor in executors.values():
                closer = getattr(executor, "aclose", None)
                if closer is not None:
                    await closer()
            await store.close()
            await redis.aclose()

    app = FastAPI(
        title="agentflow-engine",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    @app.middleware("http")
    async def guard(request: Request, call_next):
        trace_id = extract_trace_id({k.lower(): v for k, v in request.headers.items()})
        token = current_trace_id.set(trace_id)
        try:
            path = request.url.path
            if path.startswith("/internal") and path != "/internal/health":
                provided = request.headers.get("x-internal-token", "")
                if not hmac.compare_digest(provided, settings.internal_token):
                    return problem(401, "AGT-AUTH-001", "Unauthorized")
            response = await call_next(request)
            response.headers["X-Trace-Id"] = trace_id
            return response
        finally:
            current_trace_id.reset(token)

    @app.exception_handler(EngineError)
    async def engine_error_handler(_: Request, exc: EngineError):
        status = _ERROR_STATUS.get(exc.code, 500)
        return problem(status, exc.code, exc.code, exc.message)

    @app.exception_handler(ValidationError)
    async def validation_error(_: Request, exc: ValidationError):
        return problem(
            422,
            "DSL-VALIDATION-001",
            "Validation failed",
            errors=[
                {"path": ".".join(str(p) for p in e["loc"]), "message": e["msg"]}
                for e in exc.errors()
            ],
        )

    app.include_router(internal_router)
    return app


app = create_app()
