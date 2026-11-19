"""E2E：裁决的 MQ 消费通道（引擎 ← af-gate-decision）。

三个断言：
1. 消费端真的连上了 broker（`/internal/mq/status` 的 connected）；
2. 一次真实的审批裁决会被引擎从 MQ 消费到（consumed 增长）；
3. 消费到的这条消息**有账可查**：记成 applied / duplicate / stale 之一，
   且 invalid、failed 不增长。

第 2 条是关键：只断言 connected 会漏掉「连上了但拉不到消息」这类半死状态。
第 3 条刻意不锚定「必须是 duplicate」——HTTP 是快路径，但两者的先后由调度决定：
HTTP 先落地则 MQ 这条记 duplicate 或 stale，MQ 抢先则记 applied。钉死其中一个
会在 CI 上随机翻车（本轮就是这么发现的）。
未开启 MQ 的环境（`AF_MQ_ENABLED=false`）跳过，不算失败——本地纯 HTTP 也能跑。
"""

from __future__ import annotations

import time

import pytest

from .conftest import SPACE_A, engine_mq_status, headers, new_idem, wait_terminal
from .test_gate_approval import _gate_dsl, _publish, _wait_status


def _enabled_status() -> dict:
    status = engine_mq_status()
    if status is None:
        pytest.skip("引擎内部端点不可达（AF_E2E_ENGINE_URL 未指向运行中的引擎）")
    if not status.get("enabled"):
        pytest.skip(f"MQ 消费端未开启：{status.get('reason', status)}")
    return status


def _accounted(status: dict) -> int:
    return sum(status.get(key, 0) for key in ("applied", "duplicate", "stale"))


def _wait(predicate, timeout_s: float) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.5)
    return False


def test_mq_consumer_connects_to_broker():
    status = _enabled_status()
    assert _wait(lambda: (engine_mq_status() or {}).get("connected"), 30), status


def test_approval_decision_is_consumed_from_mq(client, admin_token, editor_token):
    base = _enabled_status()
    assert _wait(lambda: (engine_mq_status() or {}).get("connected"), 30)
    before_consumed = base.get("consumed", 0)
    before_accounted = _accounted(base)
    before_invalid = base.get("invalid", 0)
    before_failed = base.get("failed", 0)

    wf_id = _publish(client, editor_token, admin_token, _gate_dsl())
    triggered = client.post(f"/api/v1/workflows/{wf_id}/executions",
                            json={"idempotencyKey": new_idem(),
                                  "variables": {"question": "上线"}},
                            headers=headers(editor_token, SPACE_A))
    assert triggered.status_code == 200, triggered.text
    execution_id = triggered.json()["data"]["executionId"]
    _wait_status(client, editor_token, execution_id, "SUSPENDED")

    resolved = client.post(
        f"/api/v1/executions/{execution_id}/gates/gate/resolve",
        json={"decision": "APPROVED", "comment": "同意"},
        headers=headers(admin_token, SPACE_A))
    assert resolved.status_code == 200, resolved.text
    assert wait_terminal(client, editor_token, SPACE_A, execution_id)["execution"]["status"] \
        == "SUCCEEDED"

    assert _wait(lambda: (engine_mq_status() or {}).get("consumed", 0) > before_consumed, 30), \
        "裁决消息没有被引擎从 MQ 消费到"
    after = engine_mq_status() or {}
    assert _accounted(after) > before_accounted, f"消费到了但没算账：{after}"
    assert after.get("invalid", 0) == before_invalid, after
    assert after.get("failed", 0) == before_failed, after
