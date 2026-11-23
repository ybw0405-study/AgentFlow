"""eval harness 回归：离线报告必须与入库基线一致，漂移必须可检出。

harness 自身的语义断言在 app/eval/harness.py 里（跑场景时即执行）；
这里守住「基线文件与引擎行为不脱节」这条 CI 门禁。judge 用 MockTransport
离线验证（与 test_openai_compatible 同款替身），nightly 才走真端点。
"""

from __future__ import annotations

import copy

import httpx
import pytest

from app.commands.providers.openai_compatible import OpenAICompatibleProvider
from app.eval.__main__ import main
from app.eval.harness import EvalRegressionError, diff_scenarios, load_baseline, run_all
from app.eval.judge import judge_answer
from app.eval.specs import SCENARIOS


async def test_offline_report_matches_committed_baseline():
    report = await run_all()
    assert report == load_baseline()


def test_baseline_covers_all_scenarios():
    baseline = load_baseline()
    assert set(baseline["scenarios"]) == {s.name for s in SCENARIOS}


def test_diff_scenarios_detects_drift_and_missing():
    baseline = load_baseline()
    tampered = copy.deepcopy(baseline)
    tampered["scenarios"]["linear-order"]["tokenUsed"] += 1
    assert diff_scenarios(tampered, baseline) == ["linear-order"]
    tampered["scenarios"].pop("checkpoint-resume")
    assert "checkpoint-resume" in diff_scenarios(tampered, baseline)


def test_cli_check_baseline_exits_zero():
    assert main(["--check-baseline"]) == 0


def _judge_provider(content: str, status: int = 200) -> OpenAICompatibleProvider:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"choices": [{"message": {"content": content}}]})

    return OpenAICompatibleProvider(
        base_url="https://llm.example.test/v1",
        api_key="sk-eval-unit-test",
        default_model="eval-model",
        transport=httpx.MockTransport(handler),
    )


async def _judge(content: str, status: int = 200) -> dict:
    provider = _judge_provider(content, status)
    try:
        return await judge_answer(
            provider,
            model="eval-model",
            question="法国的首都是哪座城市？",
            reference="巴黎",
            answer="巴黎",
            trace_id="trace-eval-judge",
        )
    finally:
        await provider.aclose()


async def test_judge_parses_json_verdict():
    verdict = await _judge('{"score": 0.9, "verdict": "pass", "reason": "正确"}')
    assert verdict == {"score": 0.9, "verdict": "pass", "reason": "正确"}


async def test_judge_rejects_out_of_range_score():
    with pytest.raises(EvalRegressionError):
        await _judge('{"score": 1.5, "verdict": "pass", "reason": "x"}')


async def test_judge_rejects_non_json_output():
    with pytest.raises(EvalRegressionError):
        await _judge("这不是 JSON")


async def test_judge_rejects_upstream_failure():
    with pytest.raises(EvalRegressionError):
        await _judge("", status=500)
