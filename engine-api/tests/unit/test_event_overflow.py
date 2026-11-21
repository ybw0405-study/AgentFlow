"""事件溢出（D5）断链回归（P1-5）。

覆盖两条路径：
- 同一次执行内：NODE_COMPLETED 溢出后下游 {{ nodes.xxx.output }} 仍可用；
- 崩溃重放：从 node_io 回填完整 payload，恢复后下游引用仍可用。
"""

from __future__ import annotations

from app.commands.base import CommandResult
from app.engine.replay import rebuild_state_hydrated
from app.engine.requests import ExecuteRequest
from app.events.types import EventType

from .conftest import InMemoryEventStore, make_execute_request, make_stack
from .test_scheduler import wait_terminal

BIG_TEXT = "x" * 300_000
MAX_BYTES = 4096


class _BigOutputLLM:
    """输出足以触发 4KB 溢出的确定性 provider。"""

    name = "openai-compatible"

    async def complete(self, **kwargs) -> CommandResult:
        return CommandResult(
            ok=True,
            data={"text": BIG_TEXT, "parsed": None},
            token_usage=2,
            latency_ms=1,
            extra={"usage": {"prompt": 1, "completion": 1, "total": 2}},
        )


def _big_output_dsl() -> dict:
    # draft 输出 30 万字符 → NODE_COMPLETED 溢出；check 引用 draft.output.text，
    # 溢出断链时 StrictUndefined 会让整个执行 FAILED。
    return {
        "dslVersion": "1.0",
        "variables": {},
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "draft",
                "type": "llm",
                "config": {"model": "test-model", "promptTemplate": "hi", "maxTokens": 64},
            },
            {
                "key": "check",
                "type": "transform",
                "config": {
                    "mappings": [
                        {
                            "target": "vars.size",
                            "template": "{{ nodes.draft.output.text | length }}",
                        }
                    ]
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"size": "{{ vars.size }}"}},
            },
        ],
        "edges": [
            {"from": "start", "to": "draft"},
            {"from": "draft", "to": "check"},
            {"from": "check", "to": "end"},
        ],
    }


def _request(dsl: dict) -> ExecuteRequest:
    return ExecuteRequest.model_validate(make_execute_request(dsl, initialVariables={}))


def _stack(settings, fake_redis, store):
    return make_stack(
        settings, fake_redis, store, llm_providers={"openai-compatible": _BigOutputLLM()}
    )


async def test_overflow_downstream_reference_available_in_same_run(settings, fake_redis):
    store = InMemoryEventStore(max_bytes=MAX_BYTES)
    dispatcher, _, _ = _stack(settings, fake_redis, store)
    req = _request(_big_output_dsl())
    await dispatcher.submit(req)
    assert await wait_terminal(store, req.execution_id) == "SUCCEEDED"

    events = await store.read_all(req.execution_id)
    draft_done = next(
        e for e in events if e.event_type == EventType.NODE_COMPLETED and e.node_key == "draft"
    )
    # 落库事件只是溢出引用；完整内容转存 node_io(OUT)
    assert draft_done.payload.get("_overflow") is True
    io = await store.get_node_io(req.execution_id, "draft")
    assert io["out"]["payload"]["output"]["text"] == BIG_TEXT

    # 下游 transform 成功读到 draft.output.text（没有断链），长度随结果回传
    check_done = next(
        e for e in events if e.event_type == EventType.NODE_COMPLETED and e.node_key == "check"
    )
    assert check_done.payload["output"]["size"] == str(len(BIG_TEXT))


async def test_overflow_replay_recovers_output(settings, fake_redis):
    store = InMemoryEventStore(max_bytes=MAX_BYTES)
    dispatcher, _, _ = _stack(settings, fake_redis, store)
    req = _request(_big_output_dsl())
    await dispatcher.submit(req)
    await wait_terminal(store, req.execution_id)

    events = await store.read_all(req.execution_id)
    state = await rebuild_state_hydrated(req.execution_id, events, store)
    assert state.nodes["draft"].status == "SUCCEEDED"
    assert state.nodes["draft"].output["text"] == BIG_TEXT
    assert "draft" in state.completed_outputs()


async def test_overflow_crash_replay_resumes_downstream(settings, fake_redis):
    """崩溃重放：事件流里 draft 已完成（溢出引用），恢复后 check 仍能引用其输出。"""
    store = InMemoryEventStore(max_bytes=MAX_BYTES)
    req = _request(_big_output_dsl())
    eid = req.execution_id
    await store.put_spec(eid, req.canonical_dump())
    await store.append(eid, "ExecutionCreated", {"executionSeed": "s", "traceId": req.trace_id})
    await store.append(eid, "ExecutionStarted", {"workerId": "w"})
    for key in ("start", "draft"):
        await store.append(eid, "NodeScheduled", {"attempt": 1}, node_key=key)
        await store.append(eid, "NodeStarted", {"attempt": 1}, node_key=key)
        output = {"variables": {}} if key == "start" else {"text": BIG_TEXT, "parsed": None}
        await store.append(
            eid,
            "NodeCompleted",
            {
                "attempt": 1,
                "output": output,
                "tokenUsed": 2 if key == "draft" else 0,
                "latencyMs": 1,
            },
            node_key=key,
        )
    draft_done = next(
        e
        for e in await store.read_all(eid)
        if e.event_type == EventType.NODE_COMPLETED and e.node_key == "draft"
    )
    assert draft_done.payload.get("_overflow") is True

    dispatcher, _, _ = _stack(settings, fake_redis, store)
    await dispatcher._recover_one(eid, attempts=1)
    assert await wait_terminal(store, eid) == "SUCCEEDED"

    events = await store.read_all(eid)
    check_done = next(
        e for e in events if e.event_type == EventType.NODE_COMPLETED and e.node_key == "check"
    )
    assert check_done.payload["output"]["size"] == str(len(BIG_TEXT))
