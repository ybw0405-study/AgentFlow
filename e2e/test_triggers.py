"""E2E：外部触发。

webhook 的核心承诺是「外部系统 POST 一次就真的跑一轮」：token 鉴权、签名校验、
幂等键、停用即失效、跨租户不可见，这几条任何一条漏掉都会变成线上事故。
定时触发按分钟精度扫描，跑一轮至少要等一个分钟边界，所以单独用
AF_E2E_SCHEDULE=1 打开，默认套件不背这段等待。
"""

from __future__ import annotations

import hashlib
import hmac
import os
import time
import uuid

import pytest

from .conftest import DEMO_DSL, REAL_LLM, SPACE_A, SPACE_B, headers, wait_terminal

ECHO_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 30000, "maxParallelism": 2},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "echo", "type": "transform", "config": {
            "mappings": [{"target": "vars.answer", "template": "已收到：{{ vars.question }}"}]}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"answer": "{{ vars.answer }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "echo"},
        {"from": "echo", "to": "end"},
    ],
}


def _published_workflow(client, editor_token, admin_token, dsl: dict | None = None) -> str:
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-hook-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]
    resp = client.put(f"/api/v1/workflows/{wf_id}/draft",
                      json=dsl if dsl is not None else ECHO_DSL, headers=hdrs)
    assert resp.status_code == 200, resp.text
    resp = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    assert resp.status_code == 200, resp.text
    version_id = resp.json()["data"]["id"]
    resp = client.post(f"/api/v1/workflows/{wf_id}/publish", json={"versionId": version_id},
                       headers=headers(admin_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    return wf_id


def _create_webhook(client, editor_token, wf_id: str) -> dict:
    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "WEBHOOK"}, headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


def test_webhook_triggers_real_execution(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    hook = _create_webhook(client, editor_token, wf_id)
    assert hook["token"] and hook["secret"] and hook["enabled"] is True

    # SEC-3：create 一次性回明文，list 只回 hasSecret，不再回传密钥本体
    listed = client.get(f"/api/v1/workflows/{wf_id}/triggers",
                        headers=headers(editor_token, SPACE_A))
    assert listed.status_code == 200, listed.text
    listed_hook = next(t for t in listed.json()["data"] if t["id"] == hook["id"])
    assert listed_hook["hasSecret"] is True
    assert listed_hook.get("secret") is None

    resp = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "来自 webhook"})
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["status"] in ("PENDING", "RUNNING"), data

    detail = wait_terminal(client, editor_token, SPACE_A, data["executionId"])
    assert detail["execution"]["status"] == "SUCCEEDED", detail
    assert detail["result"]["answer"] == "已收到：来自 webhook", detail


def test_webhook_rejects_unknown_token(client):
    resp = client.post(f"/api/v1/hooks/{uuid.uuid4().hex}", json={"question": "x"})
    assert resp.status_code == 404, resp.text


def test_webhook_verifies_signature_when_present(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    hook = _create_webhook(client, editor_token, wf_id)
    url = f"/api/v1/hooks/{hook['token']}"
    body = '{"question":"签名"}'

    bad = client.post(url, content=body, headers={"X-AF-Signature": "sha256=deadbeef",
                                                  "Content-Type": "application/json"})
    assert bad.status_code == 403, bad.text

    ts = str(int(time.time()))
    digest = hmac.new(hook["secret"].encode(), f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
    # 签名覆盖时间戳：剥离 X-AF-Timestamp 后同一签名必然验签失败（P1-8）。
    no_ts = client.post(url, content=body, headers={
        "X-AF-Signature": f"sha256={digest}",
        "Content-Type": "application/json",
    })
    assert no_ts.status_code == 403, no_ts.text
    good = client.post(url, content=body, headers={
        "X-AF-Signature": f"sha256={digest}",
        "X-AF-Timestamp": ts,
        "Content-Type": "application/json",
    })
    assert good.status_code == 200, good.text
    wait_terminal(client, editor_token, SPACE_A, good.json()["data"]["executionId"])


def test_webhook_idempotency_key_is_honored(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    hook = _create_webhook(client, editor_token, wf_id)
    key = f"idem-{uuid.uuid4().hex[:10]}"
    hdrs = {"X-AF-Idempotency-Key": key}

    first = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "一次"}, headers=hdrs)
    second = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "一次"}, headers=hdrs)
    assert first.status_code == second.status_code == 200
    assert first.json()["data"]["executionId"] == second.json()["data"]["executionId"]
    assert second.json()["data"]["idempotentReplay"] is True


def test_disabled_webhook_is_rejected(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    hook = _create_webhook(client, editor_token, wf_id)

    resp = client.patch(f"/api/v1/triggers/{hook['id']}", json={"enabled": False},
                        headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 200, resp.text

    resp = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "停用"})
    assert resp.status_code == 409, resp.text


def test_trigger_list_is_tenant_scoped(client, editor_token, beta_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    _create_webhook(client, editor_token, wf_id)

    resp = client.get(f"/api/v1/workflows/{wf_id}/triggers", headers=headers(beta_token, SPACE_B))
    assert resp.status_code == 404, resp.text


def test_webhook_probe_does_not_fire(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    hook = _create_webhook(client, editor_token, wf_id)

    resp = client.post(f"/api/v1/hooks/{hook['token']}/probe")
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["enabled"] is True

    listed = client.get("/api/v1/executions", params={"definitionId": wf_id},
                        headers=headers(editor_token, SPACE_A))
    assert listed.json()["data"]["items"] == []


def test_invalid_cron_is_rejected(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "not a cron"},
                       headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 409, resp.text
    assert "cron" in resp.json()["detail"], resp.text


def test_sub_minute_cron_is_rejected(client, editor_token, admin_token):
    """秒级 cron 在分钟粒度的调度器上会被吞掉一半，所以直接拒绝而不是假装支持。"""
    wf_id = _published_workflow(client, editor_token, admin_token)
    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "*/5 * * * * *"},
                       headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 409, resp.text
    assert "seconds field must be 0" in resp.json()["detail"], resp.text


def test_cron_preview_shows_next_runs_without_creating_anything(client, editor_token,
                                                               admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)

    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers/cron-preview",
                       json={"cron": "0 9 * * *", "count": 3},
                       headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    # 5 段写法回填成 6 段规范形式，时间按触发器时区的墙钟串返回
    assert data["cron"] == "0 0 9 * * *", data
    assert data["timezone"] == "Asia/Shanghai", data
    assert len(data["times"]) == 3, data
    assert all(t.endswith(" 09:00") for t in data["times"]), data

    listed = client.get(f"/api/v1/workflows/{wf_id}/triggers",
                        headers=headers(editor_token, SPACE_A))
    assert listed.json()["data"] == [], "预览不该落触发器"


def test_cron_preview_is_tenant_scoped(client, editor_token, beta_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)

    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers/cron-preview",
                       json={"cron": "0 9 * * *"},
                       headers=headers(beta_token, SPACE_B))
    assert resp.status_code == 404, resp.text


def test_misfire_policy_round_trips(client, editor_token, admin_token):
    """补跑策略是触发器自己的属性：建、改、读回都该是同一个值。"""
    wf_id = _published_workflow(client, editor_token, admin_token)
    hdrs = headers(editor_token, SPACE_A)

    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "0 9 * * *",
                             "misfirePolicy": "SKIP"},
                       headers=hdrs)
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["misfirePolicy"] == "SKIP", data
    trigger_id = data["id"]
    try:
        resp = client.patch(f"/api/v1/triggers/{trigger_id}",
                            json={"misfirePolicy": "CATCH_UP_ALL"}, headers=hdrs)
        assert resp.status_code == 200, resp.text
        assert resp.json()["data"]["misfirePolicy"] == "CATCH_UP_ALL", resp.text

        listed = client.get(f"/api/v1/workflows/{wf_id}/triggers", headers=hdrs)
        one = next(t for t in listed.json()["data"] if t["id"] == trigger_id)
        assert one["misfirePolicy"] == "CATCH_UP_ALL", one
    finally:
        client.delete(f"/api/v1/triggers/{trigger_id}", headers=hdrs)


def test_default_misfire_policy_keeps_previous_behaviour(client, editor_token, admin_token):
    """不传策略时是「只补最近一档」：升级不该悄悄改掉既有触发器的补跑方式。"""
    wf_id = _published_workflow(client, editor_token, admin_token)
    hdrs = headers(editor_token, SPACE_A)

    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "0 9 * * *"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    try:
        assert data["misfirePolicy"] == "CATCH_UP_LATEST", data
    finally:
        client.delete(f"/api/v1/triggers/{data['id']}", headers=hdrs)


def test_unknown_misfire_policy_is_rejected(client, editor_token, admin_token):
    """拼错的策略直接 409：静默退回默认值会让用户以为「跳过了」，实际却在补跑。"""
    wf_id = _published_workflow(client, editor_token, admin_token)
    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "0 9 * * *",
                             "misfirePolicy": "CATCHUP"},
                       headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 409, resp.text
    assert "misfirePolicy" in resp.json()["detail"], resp.text


@pytest.mark.skipif(os.environ.get("AF_E2E_SCHEDULE", "") != "1",
                    reason="定时触发按分钟触发，设置 AF_E2E_SCHEDULE=1 才跑这段等待")
def test_schedule_fires_within_a_minute(client, editor_token, admin_token):
    wf_id = _published_workflow(client, editor_token, admin_token)
    resp = client.post(f"/api/v1/workflows/{wf_id}/triggers",
                       json={"type": "SCHEDULE", "cron": "* * * * *",
                             "variables": {"question": "定时"}},
                       headers=headers(editor_token, SPACE_A))
    assert resp.status_code == 200, resp.text
    trigger_id = resp.json()["data"]["id"]
    # 调度器扫的是全库启用的 cron：跑完必须停掉，否则这条「每分钟」会一直烧 token
    try:
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            listed = client.get("/api/v1/executions", params={"definitionId": wf_id},
                                headers=headers(editor_token, SPACE_A))
            items = listed.json()["data"]["items"]
            if items:
                detail = wait_terminal(client, editor_token, SPACE_A, items[0]["id"])
                assert detail["execution"]["status"] == "SUCCEEDED", detail
                assert detail["result"]["answer"] == "已收到：定时", detail
                fired = client.get(f"/api/v1/workflows/{wf_id}/triggers",
                                   headers=headers(editor_token, SPACE_A))
                schedule = next(t for t in fired.json()["data"] if t["id"] == trigger_id)
                # 档位与真正跑的时间分开记：迟到与否一眼能看出来
                assert schedule["lastSlotAt"], schedule
                assert schedule["nextFireAt"], schedule
                return
            time.sleep(5)
        raise AssertionError("定时触发在 150s 内没有产生执行")
    finally:
        client.patch(f"/api/v1/triggers/{trigger_id}", json={"enabled": False},
                     headers=headers(editor_token, SPACE_A))


def test_concurrency_limit_round_trips(client, editor_token, admin_token):
    """并发上限是工作流的属性：可读、可改、越界拒绝，0 表示恢复不限。"""
    wf_id = _published_workflow(client, editor_token, admin_token)
    hdrs = headers(editor_token, SPACE_A)

    detail = client.get(f"/api/v1/workflows/{wf_id}", headers=hdrs).json()["data"]
    assert detail["definition"]["maxConcurrent"] == 0, detail

    resp = client.patch(f"/api/v1/workflows/{wf_id}", json={"maxConcurrent": 3}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    detail = client.get(f"/api/v1/workflows/{wf_id}", headers=hdrs).json()["data"]
    assert detail["definition"]["maxConcurrent"] == 3, detail

    # 上界之外直接 400：把闸门开到 1000 等于没有闸门
    bad = client.patch(f"/api/v1/workflows/{wf_id}", json={"maxConcurrent": 65}, headers=hdrs)
    assert bad.status_code == 400, bad.text

    client.patch(f"/api/v1/workflows/{wf_id}", json={"maxConcurrent": 0}, headers=hdrs)
    detail = client.get(f"/api/v1/workflows/{wf_id}", headers=hdrs).json()["data"]
    assert detail["definition"]["maxConcurrent"] == 0, detail


@pytest.mark.skipif(not REAL_LLM, reason="需要一个跑得够久的执行来占住并发名额")
def test_concurrency_limit_rejects_second_run_while_one_is_running(
        client, editor_token, admin_token):
    """上限 1 时第二个入口必须被挡住，且是「明确拒绝」而不是插一条永远不开始的执行。

    用真实模型的工作流：LLM 节点要跑几秒，第一个执行在第二个请求发出时一定还占着名额。
    """
    wf_id = _published_workflow(client, editor_token, admin_token, dsl=DEMO_DSL)
    hdrs = headers(editor_token, SPACE_A)
    client.patch(f"/api/v1/workflows/{wf_id}", json={"maxConcurrent": 1}, headers=hdrs)
    hook = _create_webhook(client, editor_token, wf_id)

    first = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "第一个"})
    assert first.status_code == 200, first.text

    second = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "第二个"})
    assert second.status_code == 429, second.text
    assert "WF-CONCURRENCY-001" in second.text, second.text

    detail = wait_terminal(client, editor_token, SPACE_A, first.json()["data"]["executionId"])
    assert detail["execution"]["status"] == "SUCCEEDED", detail

    # 名额释放后必须还能进来：限流是「等一等」，不是「永久拉黑」
    third = client.post(f"/api/v1/hooks/{hook['token']}", json={"question": "第三个"})
    assert third.status_code == 200, third.text
    wait_terminal(client, editor_token, SPACE_A, third.json()["data"]["executionId"])
