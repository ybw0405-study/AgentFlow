"""回归场景的固定 DSL 与入参。全部是数据，不含任何执行逻辑。

场景覆盖引擎的五个核心语义：节点执行顺序、可重试节点的退避重试、condition
分支选择、parallel 汇合、checkpoint 中断→续跑。DSL 一旦入库就是契约的一部分：
改场景等于改回归口径，必须连同基线一起评审。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

_RETRY_DEFAULTS = {
    "timeoutMs": 5000,
    "maxParallelism": 4,
    "retry": {"maxAttempts": 3, "backoffMs": 100, "backoffMultiplier": 2},
}


@dataclass(frozen=True)
class Scenario:
    name: str
    dsl: dict[str, Any]
    initial_variables: dict[str, Any] = field(default_factory=dict)
    # 同层并发节点的完成顺序不受调度保证，只有线性链路的场景才比对精确顺序
    strict_order: bool = True
    # llm provider 前 N 次调用脚本化抛瞬时错误（0 = 不注入故障）
    fail_times: int = 0
    checkpoint_interval: int = 10
    # 非空时在该节点 NodeStarted 落库后模拟进程崩溃，再换新 dispatcher 续跑
    interrupt_node: str | None = None


def _linear_order_dsl() -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {
            "question": {"type": "string", "required": True},
            "prepped": {"type": "string"},
        },
        "defaults": _RETRY_DEFAULTS,
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "prepare",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.prepped", "template": "prepared"}]},
            },
            {
                "key": "draft",
                "type": "llm",
                "config": {
                    "model": "eval-model",
                    "promptTemplate": "请回答：{{ vars.question }}（{{ vars.prepped }}）",
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
                "key": "end",
                "type": "end",
                "config": {
                    "resultMapping": {
                        "answer": "{{ nodes.draft.output.text }}",
                        "prepped": "{{ vars.prepped }}",
                        "query": "{{ nodes.enrich.output.result.query }}",
                    }
                },
            },
        ],
        "edges": [
            {"from": "start", "to": "prepare"},
            {"from": "prepare", "to": "draft"},
            {"from": "draft", "to": "enrich"},
            {"from": "enrich", "to": "end"},
        ],
    }


def _llm_retry_dsl(max_attempts: int) -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {},
        "defaults": {
            "timeoutMs": 5000,
            "maxParallelism": 4,
            "retry": {
                "maxAttempts": max_attempts,
                "backoffMs": 100,
                "backoffMultiplier": 2,
            },
        },
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "call",
                "type": "llm",
                "config": {"model": "eval-model", "promptTemplate": "重试校验", "maxTokens": 16},
            },
            {
                "key": "end",
                "type": "end",
                "config": {"resultMapping": {"answer": "{{ nodes.call.output.text }}"}},
            },
        ],
        "edges": [{"from": "start", "to": "call"}, {"from": "call", "to": "end"}],
    }


def _tool_no_retry_dsl() -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {},
        "defaults": _RETRY_DEFAULTS,
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "flaky",
                "type": "tool",
                "config": {
                    "connectorId": "test.flaky",
                    "operation": "flaky",
                    "idempotent": False,
                },
            },
            {"key": "end", "type": "end", "config": {"resultMapping": {"status": "done"}}},
        ],
        "edges": [{"from": "start", "to": "flaky"}, {"from": "flaky", "to": "end"}],
    }


def _condition_dsl() -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {
            "score": {"type": "integer", "required": True},
            "grade": {"type": "string"},
            "tail": {"type": "string"},
        },
        "defaults": _RETRY_DEFAULTS,
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


def _parallel_join_dsl() -> dict[str, Any]:
    def branch(side: str, literal: str) -> dict[str, Any]:
        return {
            "nodes": [
                {
                    "key": "t",
                    "type": "transform",
                    "config": {"mappings": [{"target": f"vars.{side}_out", "template": literal}]},
                },
                {
                    "key": "end",
                    "type": "end",
                    "config": {"resultMapping": {"side": side}},
                },
            ],
            "edges": [{"from": "t", "to": "end"}],
        }

    return {
        "dslVersion": "1.0",
        "variables": {"left_out": {"type": "string"}, "right_out": {"type": "string"}},
        "defaults": _RETRY_DEFAULTS,
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "par",
                "type": "parallel",
                "config": {
                    "join": "all",
                    "maxConcurrency": 2,
                    "branches": {"left": branch("left", "L"), "right": branch("right", "R")},
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {
                    "resultMapping": {
                        "left": "{{ nodes.par.output.merged.left.side }}",
                        "right": "{{ nodes.par.output.merged.right.side }}",
                    }
                },
            },
        ],
        "edges": [{"from": "start", "to": "par"}, {"from": "par", "to": "end"}],
    }


def _checkpoint_resume_dsl() -> dict[str, Any]:
    return {
        "dslVersion": "1.0",
        "variables": {"prepped": {"type": "string"}},
        "defaults": _RETRY_DEFAULTS,
        "nodes": [
            {"key": "start", "type": "start", "config": {}},
            {
                "key": "prepare",
                "type": "transform",
                "config": {"mappings": [{"target": "vars.prepped", "template": "prepared"}]},
            },
            {
                "key": "nap",
                "type": "tool",
                "config": {
                    "connectorId": "test.echo",
                    "operation": "sleep",
                    "paramsMapping": {"sleepMs": "500"},
                },
            },
            {
                "key": "finalize",
                "type": "llm",
                "config": {
                    "model": "eval-model",
                    "promptTemplate": "续跑校验：{{ vars.prepped }}",
                    "maxTokens": 32,
                },
            },
            {
                "key": "end",
                "type": "end",
                "config": {
                    "resultMapping": {
                        "prepped": "{{ vars.prepped }}",
                        "answer": "{{ nodes.finalize.output.text }}",
                    }
                },
            },
        ],
        "edges": [
            {"from": "start", "to": "prepare"},
            {"from": "prepare", "to": "nap"},
            {"from": "nap", "to": "finalize"},
            {"from": "finalize", "to": "end"},
        ],
    }


SCENARIOS: tuple[Scenario, ...] = (
    Scenario(
        name="linear-order",
        dsl=_linear_order_dsl(),
        initial_variables={"question": "AgentFlow 如何保证恰好一次？"},
    ),
    Scenario(name="retry-backoff", dsl=_llm_retry_dsl(3), fail_times=2),
    Scenario(name="retry-exhausted", dsl=_llm_retry_dsl(2), fail_times=99),
    Scenario(name="tool-no-retry", dsl=_tool_no_retry_dsl()),
    Scenario(name="condition-high", dsl=_condition_dsl(), initial_variables={"score": 80}),
    Scenario(name="condition-low", dsl=_condition_dsl(), initial_variables={"score": 30}),
    Scenario(name="parallel-join", dsl=_parallel_join_dsl(), strict_order=False),
    Scenario(
        name="checkpoint-resume",
        dsl=_checkpoint_resume_dsl(),
        checkpoint_interval=3,
        interrupt_node="nap",
    ),
)
