"""单元测试共享装置：内存事件库 + fakeredis + 演示 DSL。"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, datetime
from typing import Any

import fakeredis.aioredis
import pytest

from app.config import Settings
from app.events.types import TERMINAL_EVENTS, Event, EventType


class InMemoryEventStore:
    """EventStore 的内存替身（单元测试用；PG 行为由集成测试覆盖）。"""

    def __init__(self) -> None:
        self.events: list[Event] = []
        self.io: dict[tuple, dict] = {}
        self.specs: dict[uuid.UUID, dict] = {}
        self._seq = 0

    async def append(self, execution_id, event_type, payload, *, node_key=None, node_io_spill=None):
        self._seq += 1
        version = (
            max(
                (e.aggregate_version for e in self.events if e.execution_id == execution_id),
                default=0,
            )
            + 1
        )
        ev = Event(
            seq=self._seq,
            execution_id=execution_id,
            event_type=EventType(event_type),
            node_key=node_key,
            payload=json.loads(json.dumps(payload, default=str)),
            aggregate_version=version,
            created_at=datetime.now(UTC),
        )
        self.events.append(ev)
        if node_io_spill is not None and node_key is not None:
            await self.put_node_io(execution_id, node_key, "OUT", node_io_spill)
        return ev

    async def read_all(self, execution_id):
        return sorted(
            (e for e in self.events if e.execution_id == execution_id),
            key=lambda e: e.aggregate_version,
        )

    async def tail(self, after_seq, limit):
        return [e for e in self.events if e.seq > after_seq][:limit]

    async def tail_for_execution(self, execution_id, after_seq, limit):
        return [e for e in self.events if e.execution_id == execution_id and e.seq > after_seq][
            :limit
        ]

    async def open_executions(self):
        out = []
        for eid, _ in self.specs.items():
            evs = await self.read_all(eid)
            if not evs or evs[-1].event_type not in TERMINAL_EVENTS:
                out.append(eid)
        return out

    async def suspended_gates(self):
        out = []
        for execution_id in {e.execution_id for e in self.events}:
            evs = await self.read_all(execution_id)
            if any(e.event_type in TERMINAL_EVENTS for e in evs):
                continue
            suspended = [e for e in evs if str(e.event_type) == "GateSuspended"]
            resolved = [e for e in evs if str(e.event_type) == "GateResolved"]
            for ev in suspended:
                if not any(
                    r.node_key == ev.node_key and r.aggregate_version > ev.aggregate_version
                    for r in resolved
                ):
                    out.append((execution_id, ev.node_key, ev.payload))
        return out

    async def put_spec(self, execution_id, spec):
        self.specs.setdefault(execution_id, spec)

    async def get_spec(self, execution_id):
        return self.specs.get(execution_id)

    async def put_node_io(self, execution_id, node_key, direction, payload):
        self.io[(execution_id, node_key, direction)] = {"payload": payload, "contentHash": "mem"}

    async def get_node_io(self, execution_id, node_key):
        def get(d):
            v = self.io.get((execution_id, node_key, d))
            return {"payload": v["payload"], "contentHash": v["contentHash"]} if v else None

        return {"in": get("IN"), "out": get("OUT")}


class NullPublisher:
    def __init__(self) -> None:
        self.published: list[dict] = []

    async def publish(self, event_dict: dict) -> None:
        self.published.append(event_dict)


@pytest.fixture
def settings() -> Settings:
    return Settings(internal_token="test-token")


@pytest.fixture
def fake_redis():
    return fakeredis.aioredis.FakeRedis(decode_responses=True)


@pytest.fixture
def mem_store() -> InMemoryEventStore:
    return InMemoryEventStore()


@pytest.fixture
def demo_dsl() -> dict[str, Any]:
    """演示工作流「LLM → 工具 → LLM」（architecture.md §6.4）。"""
    return {
        "dslVersion": "1.0",
        "variables": {"question": {"type": "string", "required": True, "pii": True}},
        "defaults": {
            "timeoutMs": 5000,
            "maxParallelism": 4,
            "retry": {"maxAttempts": 3, "backoffMs": 100, "backoffMultiplier": 2},
        },
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "draft",
                "type": "llm",
                "config": {
                    "model": "mock-large",
                    "promptTemplate": "请根据问题起草答案要点：{{ vars.question }}",
                    "maxTokens": 64,
                },
            },
            {
                "key": "enrich",
                "type": "tool",
                "config": {
                    "connectorId": "test.search",
                    "operation": "search",
                    "paramsMapping": {"q": "{{ vars.question }}"},
                },
            },
            {
                "key": "final",
                "type": "llm",
                "config": {
                    "model": "mock-large",
                    "promptTemplate": "要点：{{ nodes.draft.output.text }}\n"
                    "检索：{{ nodes.enrich.output.result }}\n"
                    "问题：{{ vars.question }}",
                    "maxTokens": 64,
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ nodes.final.output.text }}"}},
            },
        ],
        "edges": [
            {"from": "start", "to": "draft"},
            {"from": "start", "to": "enrich"},
            {"from": "draft", "to": "final"},
            {"from": "enrich", "to": "final"},
            {"from": "final", "to": "end"},
        ],
    }


def make_stack(settings, redis, store, *, llm_providers=None, subworkflow_resolver=None):
    """组装 dispatcher 全套（内存 store + fakeredis）。

    单测没有 connector-service，tool 节点用离线夹具顶替；被测的是调度、重试与幂等，
    不是连接器的真实出网行为（那部分由 test_connector_remote 覆盖）。
    """
    from app.engine.dispatcher import Dispatcher
    from app.events.log import EventLog
    from app.governance.budget import BudgetLedger
    from app.nodes import build_executors
    from app.nodes.tool import ConnectorRegistry, ToolExecutor
    from tests.support.mocks import FakeLLMProvider, FakeToolConnector

    event_log = EventLog(store, NullPublisher())
    budget = BudgetLedger(redis)
    executors = build_executors(
        redis, llm_providers or {"openai-compatible": FakeLLMProvider()}, subworkflow_resolver
    )
    executors["tool"] = ToolExecutor(ConnectorRegistry(lambda cid: FakeToolConnector(redis, cid)))
    dispatcher = Dispatcher(settings, store, event_log, redis, executors, budget)
    return dispatcher, event_log, budget


def make_execute_request(demo_dsl: dict, **overrides) -> dict[str, Any]:
    req = {
        "executionId": str(uuid.uuid4()),
        "definitionId": "def-demo",
        "versionId": "ver-demo-1",
        "tenantId": "tenant-a",
        "spaceId": "space-a",
        "triggeredBy": "user-1",
        "idempotencyKey": uuid.uuid4().hex,
        "versionSnapshot": demo_dsl,
        "authorizedConnectorIds": ["test.search", "test.echo", "test.flaky"],
        "initialVariables": {"question": "AgentFlow 如何保证恰好一次？"},
        "policy": {"tokenBudget": 8000, "maxSteps": 30, "deadlineMs": 60000},
        "traceId": uuid.uuid4().hex,
    }
    req.update(overrides)
    return req
