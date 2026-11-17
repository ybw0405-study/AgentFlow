"""loop / parallel 子图执行（P1 / US-03）。

- loop：按数组迭代，独立 nodeKey（loop#i#child），collect 汇总；
- loop 超限：LOOP_LIMIT_EXCEEDED；
- parallel：join=all 合并、join=first_success 取首个成功。
"""

from __future__ import annotations

from app.engine.requests import ExecuteRequest

from .conftest import make_execute_request, make_stack
from .test_scheduler import wait_terminal


def _loop_dsl(max_iterations: int = 10, items: int = 3) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {
            "tags": {"type": "array", "required": True},
            "collected": {"type": "array", "required": False},
        },
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "loop",
                "type": "loop",
                "config": {
                    "itemsExpression": "vars.tags",
                    "itemVar": "tag",
                    "indexVar": "i",
                    "maxIterations": max_iterations,
                    "concurrency": 2,
                    "body": {
                        "nodes": [
                            {
                                "key": "body",
                                "type": "transform",
                                "config": {
                                    "mappings": [
                                        {"target": "vars.upper", "template": "{{ vars.tag }}"}
                                    ]
                                },
                            },
                            {
                                "key": "end",
                                "type": "end",
                                "config": {"resultMapping": {"value": "{{ vars.tag }}"}},
                            },
                        ],
                        "edges": [{"from": "body", "to": "end"}],
                    },
                    "collect": {"into": "collected", "item": "value"},
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"n": "{{ nodes.loop.output.count }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "loop"}, {"from": "loop", "to": "end"}],
        "_items_hint": items,
    }


async def test_loop_iterations_and_collect(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    dsl = _loop_dsl()
    dsl.pop("_items_hint")
    req = ExecuteRequest.model_validate(
        make_execute_request(dsl, initialVariables={"tags": ["a", "b", "c"]})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"

    events = await mem_store.read_all(req.execution_id)
    completed = [e.node_key for e in events if e.event_type == "NodeCompleted" and e.node_key]
    # 每轮 body + end 各一次，key 前缀 loop#i#
    assert any(k.startswith("loop#0#") for k in completed), completed
    assert any(k.startswith("loop#1#") for k in completed), completed
    assert any(k.startswith("loop#2#") for k in completed), completed
    loop_out = next(
        e.payload["output"]
        for e in events
        if e.event_type == "NodeCompleted" and e.node_key == "loop"
    )
    assert loop_out["count"] == 3
    assert loop_out["collected"] == ["a", "b", "c"], loop_out


async def test_loop_limit_exceeded(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    dsl = _loop_dsl(max_iterations=2)
    dsl.pop("_items_hint")
    req = ExecuteRequest.model_validate(
        make_execute_request(dsl, initialVariables={"tags": ["a", "b", "c"]})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "FAILED"
    events = await mem_store.read_all(req.execution_id)
    failed = [e for e in events if e.event_type == "ExecutionFailed"]
    assert failed and "LOOP_LIMIT_EXCEEDED" in failed[-1].payload.get("errorCode", "")


def _parallel_dsl(join: str) -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {"label": {"type": "string", "required": True}},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "par",
                "type": "parallel",
                "config": {
                    "join": join,
                    "maxConcurrency": 2,
                    "branches": {
                        "left": {
                            "nodes": [
                                {
                                    "key": "t",
                                    "type": "transform",
                                    "config": {
                                        "mappings": [{"target": "vars.left_out", "template": "L"}]
                                    },
                                },
                                {
                                    "key": "end",
                                    "type": "end",
                                    "config": {"resultMapping": {"side": "left"}},
                                },
                            ],
                            "edges": [{"from": "t", "to": "end"}],
                        },
                        "right": {
                            "nodes": [
                                {
                                    "key": "t",
                                    "type": "transform",
                                    "config": {
                                        "mappings": [{"target": "vars.right_out", "template": "R"}]
                                    },
                                },
                                {
                                    "key": "end",
                                    "type": "end",
                                    "config": {"resultMapping": {"side": "right"}},
                                },
                            ],
                            "edges": [{"from": "t", "to": "end"}],
                        },
                    },
                },
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"done": "ok"}}},
        ],
        "edges": [{"from": "start", "to": "par"}, {"from": "par", "to": "end"}],
    }


async def test_parallel_join_all(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_parallel_dsl("all"), initialVariables={"label": "x"})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"
    events = await mem_store.read_all(req.execution_id)
    out = next(
        e.payload["output"]
        for e in events
        if e.event_type == "NodeCompleted" and e.node_key == "par"
    )
    assert set(out["merged"]) == {"left", "right"}, out
    assert out["merged"]["left"]["side"] == "left"
    assert out["merged"]["right"]["side"] == "right"


async def test_parallel_first_success(settings, fake_redis, mem_store):
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store)
    req = ExecuteRequest.model_validate(
        make_execute_request(_parallel_dsl("first_success"), initialVariables={"label": "x"})
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"
    events = await mem_store.read_all(req.execution_id)
    out = next(
        e.payload["output"]
        for e in events
        if e.event_type == "NodeCompleted" and e.node_key == "par"
    )
    assert out["branch"] in ("left", "right"), out
