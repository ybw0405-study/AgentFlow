"""DSL → DAG 编译。假定已通过 validate_dsl 校验。"""

from __future__ import annotations

from dataclasses import dataclass, field

from .models import AnyNode, Edge, WorkflowDsl


@dataclass
class Dag:
    nodes: dict[str, AnyNode]
    out_edges: dict[str, list[Edge]] = field(default_factory=dict)
    in_edges: dict[str, list[Edge]] = field(default_factory=dict)
    layers: list[list[str]] = field(default_factory=list) # 拓扑分层

    def end_keys(self) -> list[str]:
        """全部 end 节点（DSL 声明顺序）。分支场景下 end 可以有多个，收口要挑真正跑到的那个。"""
        return [k for k, n in self.nodes.items() if n.type == "end"]

    def dependencies_of(self, key: str) -> list[str]:
        return [e.from_ for e in self.in_edges.get(key, [])]


def compile_dag(dsl: WorkflowDsl) -> Dag:
    nodes = {n.key: n for n in dsl.nodes}
    out_edges: dict[str, list[Edge]] = {k: [] for k in nodes}
    in_edges: dict[str, list[Edge]] = {k: [] for k in nodes}
    for e in dsl.edges:
        out_edges[e.from_].append(e)
        in_edges[e.to].append(e)

    # Kahn 分层
    in_degree = {k: len(v) for k, v in in_edges.items()}
    current = [k for k, d in in_degree.items() if d == 0]
    layers: list[list[str]] = []
    while current:
        layers.append(sorted(current))
        nxt: list[str] = []
        for k in current:
            for e in out_edges[k]:
                in_degree[e.to] -= 1
                if in_degree[e.to] == 0:
                    nxt.append(e.to)
        current = nxt
    if sum(len(layer) for layer in layers) != len(nodes): # 防御：校验期已排除
        raise ValueError("DSL-CYCLE-001: cycle detected at compile time")
    return Dag(nodes=nodes, out_edges=out_edges, in_edges=in_edges, layers=layers)
