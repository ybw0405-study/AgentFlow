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

from .conftest import SPACE_A, SPACE_B, headers, wait_terminal

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


def _published_workflow(client, editor_token, admin_token) -> str:
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-hook-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]
    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=ECHO_DSL, headers=hdrs)
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

    digest = hmac.new(hook["secret"].encode(), body.encode(), hashlib.sha256).hexdigest()
    good = client.post(url, content=body, headers={
        "X-AF-Signature": f"sha256={digest}",
        "X-AF-Timestamp": str(int(time.time())),
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
