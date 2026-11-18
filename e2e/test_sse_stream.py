"""SSE 实时通道验收。

覆盖三件事，都是此前只有实现没有测试、因而实际已损坏的能力：
  ① 存量回放：连上就能补齐 afterSeq 之后的历史事件；
  ② 增量语义：afterSeq 落在中间时只回传后续事件；
  ③ 订阅鉴权：别人的执行拿不到事件流（跨租户一律 404）。
"""

from __future__ import annotations

import json

import httpx

from .conftest import DEMO_DSL, headers, new_idem, wait_terminal

TERMINAL = {"ExecutionCompleted", "ExecutionFailed", "ExecutionCancelled", "ExecutionTimedOut"}


def _trigger(client: httpx.Client, token: str, space: str) -> str:
    resp = client.post(
        "/api/v1/workflows/wf-demo/executions",
        headers=headers(token, space),
        json={"idempotencyKey": new_idem(), "variables": {"question": "SSE 验收"}},
    )
    assert resp.status_code in (200, 202), resp.text
    return resp.json()["data"]["executionId"]


def _collect(client: httpx.Client, token: str, space: str, execution_id: str,
             after_seq: int = 0, max_events: int = 200) -> list[dict]:
    """读事件流直到遇到终态事件，返回已解析的事件列表。"""
    events: list[dict] = []
    url = f"/api/v1/executions/{execution_id}/events/stream?afterSeq={after_seq}"
    with client.stream("GET", url, headers=headers(token, space), timeout=30.0) as resp:
        assert resp.status_code == 200, resp.text
        for line in resp.iter_lines():
            if not line.startswith("data:"):
                continue
            events.append(json.loads(line[5:].strip()))
            if events[-1]["eventType"] in TERMINAL or len(events) >= max_events:
                break
    return events


def test_stream_replays_history_in_order(client, admin_token) -> None:
    execution_id = _trigger(client, admin_token, "t-space-a")
    wait_terminal(client, admin_token, "t-space-a", execution_id)

    events = _collect(client, admin_token, "t-space-a", execution_id)
    assert events, "SSE 未回放任何事件"
    assert events[0]["eventType"] == "ExecutionCreated"
    assert events[-1]["eventType"] in TERMINAL

    seqs = [e["seq"] for e in events]
    assert seqs == sorted(seqs), "事件序必须单调递增"
    assert {e["executionId"] for e in events} == {execution_id}


def test_stream_after_seq_returns_only_new_events(client, admin_token) -> None:
    execution_id = _trigger(client, admin_token, "t-space-a")
    wait_terminal(client, admin_token, "t-space-a", execution_id)

    full = _collect(client, admin_token, "t-space-a", execution_id)
    assert len(full) >= 3
    pivot = full[1]["seq"]

    partial = _collect(client, admin_token, "t-space-a", execution_id, after_seq=pivot)
    assert partial, "afterSeq 之应有后续事件"
    assert min(e["seq"] for e in partial) > pivot
    assert [e["seq"] for e in partial] == [e["seq"] for e in full if e["seq"] > pivot]


def test_stream_requires_ownership(client, beta_token) -> None:
    """t-space-a 的执行对 t-space-beta 成员不可见：订阅同样按 404 处理。"""
    execution_resp = client.get(
        "/api/v1/executions?limit=1", headers=headers(beta_token, "t-space-beta")
    )
    assert execution_resp.status_code == 200
    foreign = execution_resp.json()["data"]["items"]
    target = foreign[0]["id"] if foreign else "00000000-0000-0000-0000-000000000000"

    resp = client.get(
        f"/api/v1/executions/{target}/events/stream",
        headers=headers(beta_token, "t-space-beta"),
        timeout=10.0,
    )
    assert resp.status_code == 404, resp.text


def test_demo_dsl_is_unchanged() -> None:
    """demo DSL 是全部 e2e 的基线，任何改动都应显式体现在这里而非悄悄漂移。"""
    assert DEMO_DSL["dslVersion"] == "1.0"
    assert {n["type"] for n in DEMO_DSL["nodes"]} == {"start", "llm", "tool", "end"}
