"""subworkflow 节点（P1）：子工作流 DSL 解析 + input/output mapping + 结果回传。"""

from __future__ import annotations

from typing import Any

from app.engine.requests import ExecuteRequest

from .conftest import make_execute_request, make_stack
from .test_scheduler import wait_terminal


class FakeResolver:
    """返回内存中的子工作流 DSL 快照。"""

    def __init__(self, dsl: dict[str, Any]) -> None:
        self._dsl = dsl
        self.calls: list[dict[str, Any]] = []

    async def resolve(self, *, definition_id, space_id, version_id) -> dict[str, Any]:
        self.calls.append(
            {"definition_id": definition_id, "space_id": space_id, "version_id": version_id}
        )
        return self._dsl


CHILD_DSL = {
    "nodes": [
        {"key": "start", "type": "start", "config": {}},
        {
            "key": "child",
            "type": "transform",
            "config": {"mappings": [{"target": "vars.child_out", "template": "{{ vars.seed }}!"}]},
        },
        {
            "key": "end",
            "type": "end",
            "config": {"resultMapping": {"value": "{{ vars.child_out }}"}},
        },
    ],
    "edges": [{"from": "start", "to": "child"}, {"from": "child", "to": "end"}],
}


def _parent_dsl() -> dict:
    return {
        "dslVersion": "1.0",
        "variables": {
            "seed": {"type": "string", "required": True},
            "result": {"type": "string", "required": False},
        },
        "defaults": {"timeoutMs": 5000, "maxParallelism": 4},
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "sub",
                "type": "subworkflow",
                "config": {
                    "definitionId": "child-def",
                    "versionPolicy": "LATEST_PUBLISHED",
                    "inputMapping": {"seed": "{{ vars.seed }}"},
                    "outputMapping": {"result": "{{ vars.value }}"},
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"final": "{{ vars.result }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "sub"}, {"from": "sub", "to": "end"}],
    }


async def test_subworkflow_resolve_and_map(settings, fake_redis, mem_store):
    resolver = FakeResolver(CHILD_DSL)
    dispatcher, _, _ = make_stack(settings, fake_redis, mem_store, subworkflow_resolver=resolver)
    req = ExecuteRequest.model_validate(
        make_execute_request(_parent_dsl(), initialVariables={"seed": "hello"}, spaceId="sp-a")
    )
    await dispatcher.submit(req)
    assert await wait_terminal(mem_store, req.execution_id) == "SUCCEEDED"

    assert resolver.calls and resolver.calls[0]["definition_id"] == "child-def"
    assert resolver.calls[0]["space_id"] == "sp-a"
    events = await mem_store.read_all(req.execution_id)
    sub_out = next(
        e.payload["output"]
        for e in events
        if e.event_type == "NodeCompleted" and e.node_key == "sub"
    )
    assert sub_out["result"] == "hello!", sub_out
    # 子工作流节点带前缀，事件可追溯
    assert any(e.node_key and e.node_key.startswith("sub#run#") for e in events)
