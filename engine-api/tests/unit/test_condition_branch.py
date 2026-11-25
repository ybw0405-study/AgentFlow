"""condition 分支与 SKIPPED 传播。

命中分支的下游执行；未命中分支及其下游标记 SKIPPED 且保留在执行树中；
汇聚节点（多入边）在至少一条激活边时仍执行。
"""

from __future__ import annotations

from app.engine.requests import ExecuteRequest

from .conftest import make_execute_request, make_stack
from .test_scheduler import wait_terminal


def _branch_dsl(score: int) -> dict:
    """gate 按 vars.score 命中 high/low；low 分支下游 skipped_tail 应级联 SKIPPED。"""
    return {
        "dslVersion": "1.0",
        "variables": {
            "score": {"type": "integer", "required": True},
            "label": {"type": "string", "required": True},
        },
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "gate",
                "type": "condition",
                "config": {
                    "cases": [
                        {"name": "high", "when": "vars.score >= 60"},
                        {"name": "low", "when": "vars.score < 60"},
                    ],
                    "elseBranch": "low",
                },
            },
            {
                "key": "yes",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.grade", "template": "PASS"}]},
            },
            {
                "key": "no",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.grade", "template": "FAIL"}]},
            },
            {
                "key": "skipped_tail",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.tail", "template": "never"}]},
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"grade": "{{ vars.grade }}"}},
            },
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


async def test_condition_taken_branch_and_skip_propagation(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_branch_dsl(80), initialVariables={"score": 80, "label": "x"})
    )
    result = await dispatcher.submit(req)
    assert result["status"] == "PENDING"
    status = await wait_terminal(mem_store, req.execution_id)
    assert status == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    statuses: dict[str, str] = {}
    for e in events:
        if e.event_type == "NodeCompleted" and e.node_key:
            statuses[e.node_key] = "SUCCEEDED"
        elif e.event_type == "NodeSkipped" and e.node_key:
            statuses[e.node_key] = "SKIPPED"

    assert statuses.get("gate") == "SUCCEEDED"
    assert statuses.get("yes") == "SUCCEEDED", statuses
    assert statuses.get("no") == "SKIPPED", statuses
    # 级联：未命中分支的下游也 SKIPPED，且保留在执行树中
    assert statuses.get("skipped_tail") == "SKIPPED", statuses
    # 汇聚节点：yes 激活、skipped_tail 未激活 → end 仍执行
    assert statuses.get("end") == "SUCCEEDED", statuses


async def test_condition_else_branch(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_branch_dsl(30), initialVariables={"score": 30, "label": "x"})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    statuses: dict[str, str] = {}
    for e in events:
        if e.event_type == "NodeCompleted" and e.node_key:
            statuses[e.node_key] = "SUCCEEDED"
        elif e.event_type == "NodeSkipped" and e.node_key:
            statuses[e.node_key] = "SKIPPED"

    assert statuses.get("no") == "SUCCEEDED", statuses
    assert statuses.get("yes") == "SKIPPED", statuses
    assert statuses.get("skipped_tail") == "SUCCEEDED", statuses
    assert statuses.get("end") == "SUCCEEDED", statuses


def _two_end_dsl(score: int) -> dict:
    """两条分支各自收口到自己的 end：最终结果必须取「真正跑到的那个」。"""
    return {
        "dslVersion": "1.0",
        "variables": {"score": {"type": "integer", "required": True}},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "gate",
                "type": "condition",
                "config": {
                    "cases": [{"name": "high", "when": "vars.score >= 60"}],
                    "elseBranch": "low",
                },
            },
            # 定义顺序刻意让 fail_end 在前：取「第一个 end」的实现会在这里翻车
            {"key": "fail_end", "type": "end", "config": {"resultMapping": {"grade": "FAIL"}}},
            {"key": "pass_end", "type": "end", "config": {"resultMapping": {"grade": "PASS"}}},
        ],
        "edges": [
            {"from": "start", "to": "gate"},
            {"from": "gate", "to": "pass_end", "branch": "high"},
            {"from": "gate", "to": "fail_end", "branch": "low"},
        ],
    }


async def test_final_result_comes_from_executed_end(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_two_end_dsl(80), initialVariables={"score": 80})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    completed = next(e for e in events if e.event_type == "ExecutionCompleted")
    assert completed.payload["result"] == {"grade": "PASS"}


async def test_final_result_else_branch_end(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_two_end_dsl(10), initialVariables={"score": 10})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    completed = next(e for e in events if e.event_type == "ExecutionCompleted")
    assert completed.payload["result"] == {"grade": "FAIL"}
