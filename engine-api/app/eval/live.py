"""真模型 llm 节点冒烟 + judge 门禁（eval-nightly 专用，默认离线流程不加载）。

事件库/租约/预算仍走内存替身与 fakeredis，但 llm 节点经真实
OpenAICompatibleProvider 出网：模板渲染、PII 二层、预算预占、Command 事件、
错误分类全部按生产路径执行。用例是固定合成问题，不含 PII、不接业务数据。
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any

import fakeredis.aioredis

from app.commands.providers.openai_compatible import OpenAICompatibleProvider
from app.config import Settings
from app.engine.requests import ExecuteRequest
from app.events.types import EventType
from tests.unit.conftest import InMemoryEventStore

from .harness import EvalRegressionError, _stack, _wait_terminal, require
from .judge import judge_answer

DEFAULT_THRESHOLD = 0.8

_CASES: tuple[dict[str, str], ...] = (
    {
        "id": "capital",
        "question": "法国的首都是哪座城市？只回答城市名。",
        "reference": "巴黎",
    },
    {
        "id": "arithmetic",
        "question": "17 + 25 等于多少？只回答数字。",
        "reference": "42",
    },
    {
        "id": "orchestration",
        "question": "用一句话说明什么是工作流编排。",
        "reference": "把多个步骤按依赖关系与控制流组织起来自动执行",
    },
)


def _smoke_dsl(model: str) -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {"question": {"type": "string", "required": True}},
        "defaults": {
            "timeoutMs": 120_000,
            "maxParallelism": 1,
            "retry": {"maxAttempts": 2, "backoffMs": 1000, "backoffMultiplier": 2},
        },
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "answer",
                "type": "llm",
                "config": {
                    "model": model,
                    "promptTemplate": "{{ vars.question }}",
                    "temperature": 0.0,
                    "maxTokens": 256,
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ nodes.answer.output.text }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "answer"}, {"from": "answer", "to": "end"}],
    }


def _smoke_request(case: dict[str, str], model: str) -> ExecuteRequest:
    eid = uuid.uuid5(uuid.NAMESPACE_URL, f"agentflow-eval-live:{case['id']}")
    trace = uuid.uuid5(uuid.NAMESPACE_URL, f"agentflow-eval-live-trace:{case['id']}")
    return ExecuteRequest.model_validate(
        {
            "executionId": str(eid),
            "definitionId": f"def-eval-live-{case['id']}",
            "versionId": "ver-eval-live-1",
            "tenantId": "tenant-eval",
            "spaceId": "space-eval",
            "triggeredBy": "eval-nightly",
            "idempotencyKey": eid.hex,
            "versionSnapshot": _smoke_dsl(model),
            "initialVariables": {"question": case["question"]},
            "policy": {"tokenBudget": 4000, "maxSteps": 10, "deadlineMs": 180_000},
            "traceId": trace.hex,
            "executionSeed": f"eval-live-seed:{case['id']}",
        }
    )


async def _run_case(
    provider: OpenAICompatibleProvider, settings: Settings, case: dict[str, str]
) -> dict[str, Any]:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    store = InMemoryEventStore()
    dispatcher = _stack(settings, redis, store, provider)
    req = _smoke_request(case, settings.llm_model)
    await dispatcher.submit(req)
    status = await _wait_terminal(store, req.execution_id, timeout_s=180)
    events = await store.read_all(req.execution_id)
    await redis.aclose()
    if status != "SUCCEEDED":
        terminal = next(e for e in reversed(events) if e.node_key is None)
        raise EvalRegressionError(
            f"llm 冒烟 {case['id']} 未成功: {status} {terminal.payload.get('errorCode')}"
        )

    completed = next(e for e in events if e.event_type == EventType.EXECUTION_COMPLETED)
    answer = completed.payload["result"]["answer"]
    require(bool(answer), f"llm 冒烟 {case['id']} 输出为空")
    issued = [e for e in events if e.event_type == EventType.COMMAND_ISSUED]
    done = [e for e in events if e.event_type == EventType.COMMAND_COMPLETED]
    require(
        len(issued) == len(done) == 1 and issued[0].payload["callId"] == done[0].payload["callId"],
        f"llm 冒烟 {case['id']} Command 事件配对被破坏",
    )
    require(completed.payload["tokenUsed"] > 0, f"llm 冒烟 {case['id']} token 计量缺失")

    entry: dict[str, Any] = {
        "id": case["id"],
        "question": case["question"],
        "status": status,
        "tokenUsed": completed.payload["tokenUsed"],
        "answer": answer[:2000],
    }
    entry.update(
        await judge_answer(
            provider,
            model=settings.llm_model,
            question=case["question"],
            reference=case["reference"],
            answer=answer,
            trace_id=req.trace_id,
        )
    )
    return entry


async def run_live(*, report_path: Path | None, threshold: float) -> int:
    settings = Settings()
    report: dict[str, Any] = {
        "mode": "live",
        "model": settings.llm_model,
        "threshold": threshold,
        "cases": [],
        "meanScore": None,
        "passed": False,
    }

    def finish(code: int) -> int:
        text = json.dumps(report, ensure_ascii=False, indent=2)
        if report_path is not None:
            report_path.write_text(text + "\n", encoding="utf-8")
        print(text)
        return code

    if not (settings.llm_base_url and settings.llm_api_key and settings.llm_model):
        report["error"] = "未配置真实模型：需要 AF_LLM_BASE_URL / AF_LLM_API_KEY / AF_LLM_MODEL"
        return finish(2)
    if not settings.llm_base_url.startswith("https://") and not settings.llm_allow_insecure_http:
        report["error"] = (
            "AF_LLM_BASE_URL 必须为 https（明文 http 需显式 AF_LLM_ALLOW_INSECURE_HTTP）"
        )
        return finish(2)

    provider = OpenAICompatibleProvider.from_settings(settings)
    try:
        for case in _CASES:
            report["cases"].append(await _run_case(provider, settings, case))
        scores = [entry["score"] for entry in report["cases"]]
        report["meanScore"] = sum(scores) / len(scores)
        report["passed"] = report["meanScore"] >= threshold
    except Exception as exc:
        report["error"] = f"{type(exc).__name__}: {exc}"
        return finish(1)
    finally:
        await provider.aclose()
    return finish(0 if report["passed"] else 1)
