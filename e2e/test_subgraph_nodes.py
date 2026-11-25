"""E2E（画布节点覆盖）：condition 分支、loop 子图迭代、parallel 并发汇合。

这三类节点的配置结构最容易被画布改坏（分支标签、子图内嵌、迭代变量、collect 汇总），
而它们只要写错，通常要等到发布或运行期才暴露。这里直接走真实链路
（建 → 存草稿 → 冻结校验 → 发布 → 触发 → 轮询到终态），确保画布产出的 DSL
在引擎侧可发布、可执行，且节点状态与产出符合预期。
"""

from __future__ import annotations

import uuid

from .conftest import SPACE_A, headers, new_idem, wait_terminal

BRANCH_LOOP_DSL = {
    "dslVersion": "1.0",
    "variables": {
        "question": {"type": "string", "required": True},
        "items": {"type": "array", "required": True},
    },
    "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {
            "key": "has_items",
            "type": "condition",
            "config": {"cases": [{"name": "has", "when": "vars.items"}], "elseBranch": "empty"},
        },
        {
            "key": "loop_1",
            "type": "loop",
            "config": {
                "itemsExpression": "vars.items",
                "itemVar": "item",
                "indexVar": "index",
                "maxIterations": 10,
                "concurrency": 2,
                "collect": {"into": "tags", "item": "value"},
                "body": {
                    "nodes": [
                        {
                            "key": "body_mid",
                            "type": "transform",
                            "config": {
                                "mappings": [
                                    {"target": "vars.tag", "template": "{{ vars.index }}:{{ vars.item }}"}
                                ]
                            },
                        },
                        {
                            "key": "body_end",
                            "type": "end",
                            "config": {"resultMapping": {"value": "{{ nodes.body_mid.output.tag }}"}},
                        },
                    ],
                    "edges": [{"from": "body_mid", "to": "body_end"}],
                },
            },
        },
        {
            "key": "answer",
            "type": "end",
            "config": {
                "resultMapping": {
                    "count": "{{ nodes.loop_1.output.count }}",
                    "first": "{{ nodes.loop_1.output.items[0].value }}",
                    "tags": "{{ vars.tags | join('-') }}",
                }
            },
        },
        {"key": "empty_end", "type": "end", "config": {"resultMapping": {"count": "0"}}},
    ],
    "edges": [
        {"from": "start", "to": "has_items"},
        {"from": "has_items", "to": "loop_1", "branch": "has"},
        {"from": "has_items", "to": "empty_end", "branch": "empty"},
        {"from": "loop_1", "to": "answer"},
    ],
}

PARALLEL_DSL = {
    "dslVersion": "1.0",
    "variables": {"question": {"type": "string", "required": True}},
    "defaults": {"timeoutMs": 30000, "maxParallelism": 4},
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {
            "key": "fan",
            "type": "parallel",
            "config": {
                "join": "all",
                "maxConcurrency": 2,
                "branches": {
                    name: {
                        "nodes": [
                            {
                                "key": "b_mid",
                                "type": "transform",
                                "config": {
                                    "mappings": [
                                        {"target": "vars.tmp", "template": f"{name}:{{{{ vars.question }}}}"}
                                    ]
                                },
                            },
                            {
                                "key": "b_end",
                                "type": "end",
                                "config": {"resultMapping": {"value": "{{ nodes.b_mid.output.tmp }}"}},
                            },
                        ],
                        "edges": [{"from": "b_mid", "to": "b_end"}],
                    }
                    for name in ("left", "right")
                },
            },
        },
        {
            "key": "end",
            "type": "end",
            "config": {
                "resultMapping": {
                    "left": "{{ nodes.fan.output.merged.left.value }}",
                    "right": "{{ nodes.fan.output.merged.right.value }}",
                }
            },
        },
    ],
    "edges": [{"from": "start", "to": "fan"}, {"from": "fan", "to": "end"}],
}


def _publish_and_run(client, editor_token, admin_token, dsl, variables) -> dict:
    """建工作流 → 存草稿 → 冻结 → 发布 → 触发 → 等到终态，返回执行详情。"""
    hdrs = headers(editor_token, SPACE_A)
    resp = client.post(
        "/api/v1/workflows", json={"name": f"e2e-canvas-{uuid.uuid4().hex[:8]}"}, headers=hdrs
    )
    assert resp.status_code == 200, resp.text
    wf_id = resp.json()["data"]["id"]

    resp = client.put(f"/api/v1/workflows/{wf_id}/draft", json=dsl, headers=hdrs)
    assert resp.status_code == 200, resp.text

    resp = client.post(f"/api/v1/workflows/{wf_id}/versions", headers=hdrs)
    assert resp.status_code == 200, resp.text
    version_id = resp.json()["data"]["id"]

    resp = client.post(
        f"/api/v1/workflows/{wf_id}/publish",
        json={"versionId": version_id},
        headers=headers(admin_token, SPACE_A),
    )
    assert resp.status_code == 200, resp.text

    resp = client.post(
        f"/api/v1/workflows/{wf_id}/executions",
        json={"idempotencyKey": new_idem(), "variables": variables},
        headers=hdrs,
    )
    assert resp.status_code == 200, resp.text
    return wait_terminal(client, editor_token, SPACE_A, resp.json()["data"]["executionId"])


def test_condition_branch_routes_into_loop(client, editor_token, admin_token):
    """命中 condition 的 has 分支：loop 逐项迭代，未命中的 empty_end 被 SKIPPED。"""
    detail = _publish_and_run(
        client, editor_token, admin_token, BRANCH_LOOP_DSL,
        {"question": "probe", "items": ["a", "b"]},
    )
    assert detail["execution"]["status"] == "SUCCEEDED", detail["execution"]

    nodes = {n["nodeKey"]: n["status"] for n in detail["nodes"]}
    assert nodes["has_items"] == "SUCCEEDED"
    assert nodes["loop_1"] == "SUCCEEDED"
    assert nodes["answer"] == "SUCCEEDED"
    assert nodes["empty_end"] == "SKIPPED" # 另一条分支不得执行
    # 每轮迭代的节点 key 形如 loop_1#0#body_mid
    assert nodes["loop_1#0#body_mid"] == "SUCCEEDED"
    assert nodes["loop_1#1#body_end"] == "SUCCEEDED"

    # end 的 resultMapping：collect 汇总 + 单项取值都按预期渲染
    assert detail["result"]["count"] == "2"
    assert detail["result"]["first"] == "0:a"
    assert sorted(detail["result"]["tags"].split("-")) == ["0:a", "1:b"]


def test_condition_else_branch_skips_loop(client, editor_token, admin_token):
    """items 为空 → else 分支命中，loop 整段 SKIPPED。"""
    detail = _publish_and_run(
        client, editor_token, admin_token, BRANCH_LOOP_DSL,
        {"question": "probe", "items": []},
    )
    assert detail["execution"]["status"] == "SUCCEEDED", detail["execution"]

    nodes = {n["nodeKey"]: n["status"] for n in detail["nodes"]}
    assert nodes["empty_end"] == "SUCCEEDED"
    assert nodes["loop_1"] == "SKIPPED"
    assert nodes["answer"] == "SKIPPED"
    assert detail["result"] == {"count": "0"}


def test_parallel_join_all_merges_branches(client, editor_token, admin_token):
    """parallel join=all：两个分支各自的 end 产出按分支名合并。"""
    detail = _publish_and_run(
        client, editor_token, admin_token, PARALLEL_DSL, {"question": "hello"}
    )
    assert detail["execution"]["status"] == "SUCCEEDED", detail["execution"]

    nodes = {n["nodeKey"]: n["status"] for n in detail["nodes"]}
    assert nodes["fan"] == "SUCCEEDED"
    assert nodes["fan#left#b_end"] == "SUCCEEDED"
    assert nodes["fan#right#b_end"] == "SUCCEEDED"
    assert detail["result"] == {"left": "left:hello", "right": "right:hello"}
