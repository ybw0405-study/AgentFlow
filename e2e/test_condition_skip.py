"""E2E（P1 / US-03）：condition 分支命中与 SKIPPED 传播（经 gateway 全链路）。"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal

BRANCH_DSL = {
    "dslVersion": "1.0",
    "variables": {
        "score": {"type": "integer", "required": True},
        "label": {"type": "string", "required": True},
        "grade": {"type": "string", "required": False},
        "tail": {"type": "string", "required": False},
    },
    "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {"key": "gate", "type": "condition", "config": {
            "cases": [
                {"name": "high", "when": "vars.score >= 60"},
                {"name": "low", "when": "vars.score < 60"},
            ],
            "elseBranch": "low",
        }},
        {"key": "yes", "type": "transform", "config": {
            "mappings": [{"target": "vars.grade", "template": "PASS"}]}},
        {"key": "no", "type": "transform", "config": {
            "mappings": [{"target": "vars.grade", "template": "FAIL"}]}},
        {"key": "skipped_tail", "type": "transform", "config": {
            "mappings": [{"target": "vars.tail", "template": "never"}]}},
        {"key": "end", "type": "end", "config": {
            "resultMapping": {"grade": "{{ vars.grade }}"}}},
    ],
    "edges": [
        {"from": "start", "to": "gate"},
        {"from": "gate", "to": "yes", "branch": "high"},
        {"from": "gate", "to": "no", "branch": "low"},
        {"from": "no", "to": "skipped_tail"},
        {"from": "yes", "to": "end"},
        {"from": "skipped_tail", "to": "end"},
    ],
}


def test_condition_branch_skips(client, editor_token, admin_token):
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post("/api/v1/workflows",
                       json={"name": f"e2e-cond-{uuid.uuid4().hex[:8]}"}, headers=hdrs)
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]

    assert client.put(f"/api/v1/workflows/{wf_id}/draft", json=BRANCH_DSL,
                      headers=hdrs).status_code == 200
    ver = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs).json()["data"]
    assert client.post(f"/api/v1/workflows/{wf_id}/publish",
                       json={"versionId": ver["id"]},
                       headers=headers(admin_token, SPACE_A)).status_code == 200

    resp = client.post(f"/api/v1/workflows/{wf_id}/executions", json={
        "idempotencyKey": new_idem(),
        "variables": {"score": 80, "label": "x"},
    }, headers=hdrs)
    assert resp.status_code == 200, resp.text
    exec_id = resp.json()["data"]["executionId"]

    detail = wait_terminal(client, editor_token, SPACE_A, exec_id)
    assert detail["execution"]["status"] == "SUCCEEDED", detail
    nodes = {n["nodeKey"]: n["status"] for n in detail["nodes"]}
    assert nodes["yes"] == "SUCCEEDED", nodes
    assert nodes["no"] == "SKIPPED", nodes
    assert nodes["skipped_tail"] == "SKIPPED", nodes  # 级联跳过且保留在树中
    assert nodes["end"] == "SUCCEEDED", nodes  # 汇聚节点仍执行
