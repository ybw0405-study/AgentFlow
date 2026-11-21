"""DSL 结构校验（编译期）。失败返回带节点路径的错误列表，对应 HTTP 422。"""

from __future__ import annotations

import ast
import hashlib
import json
import re
from typing import Any

from pydantic import Field, ValidationError

from ..config import Settings
from .models import CamelModel, WorkflowDsl

# ---- 错误码（与 architecture.md §8 对齐） ----
DSL_VALIDATION = "DSL-VALIDATION-001"
DSL_CYCLE = "DSL-CYCLE-001"
DSL_REF = "DSL-REF-001"
DSL_ORPHAN = "DSL-ORPHAN-001"
DSL_BRANCH = "DSL-BRANCH-001"

_VAR_REF = re.compile(r"\bvars\.([A-Za-z_][A-Za-z0-9_]*)")
_NODE_REF = re.compile(r"\bnodes\.([a-z][a-zA-Z0-9_-]{0,63})")
_BRANCH_NODE_TYPES = {"condition", "human_gate"}

MAX_SUBGRAPH_DEPTH = 4
"""嵌套子图的最大层数，与前端 dsl/validate.ts 及画布下钻同一口径。

以前只有前端「即时校验」有这个上限，画布导航和发布校验都能无限下钻，
README 却写成「画布最多下钻 4 层」。现在三处统一：超过 4 层明确报错，
而不是静默跳过校验。
"""


class DslError(CamelModel):
    code: str
    message: str
    node_key: str | None = None
    path: str | None = None


class ValidationResult(CamelModel):
    ok: bool
    errors: list[DslError] = Field(default_factory=list)
    normalized_dsl: dict[str, Any] | None = None
    checksum: str | None = None


def canonical_json(data: dict[str, Any]) -> str:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def checksum_of(dsl: WorkflowDsl) -> str:
    return hashlib.sha256(canonical_json(dsl.canonical_dump()).encode("utf-8")).hexdigest()


def _collect_strings(value: Any) -> list[str]:
    """递归收集配置中的所有字符串（模板与表达式都按字符串存储）。"""
    out: list[str] = []
    if isinstance(value, str):
        out.append(value)
    elif isinstance(value, dict):
        for v in value.values():
            out.extend(_collect_strings(v))
    elif isinstance(value, list):
        for v in value:
            out.extend(_collect_strings(v))
    return out


def validate_dsl(raw: dict[str, Any], settings: Settings) -> ValidationResult:
    # 1) Schema 层（Pydantic）
    try:
        dsl = WorkflowDsl.model_validate(raw)
    except ValidationError as exc:
        errors = [
            DslError(
                code=DSL_VALIDATION,
                message=e["msg"],
                path=".".join(str(p) for p in e["loc"]) or None,
            )
            for e in exc.errors()
        ]
        return ValidationResult(ok=False, errors=errors)

    errors: list[DslError] = []
    nodes = {n.key: n for n in dsl.nodes}

    # 2) 重复 nodeKey
    seen: set[str] = set()
    for n in dsl.nodes:
        if n.key in seen:
            errors.append(
                DslError(
                    code=DSL_VALIDATION, message=f"duplicate nodeKey '{n.key}'", node_key=n.key
                )
            )
        seen.add(n.key)

    # 3) 阶段门：P0 仅启用部分节点类型
    for n in dsl.nodes:
        if n.type not in settings.supported_node_types:
            errors.append(
                DslError(
                    code=DSL_VALIDATION,
                    node_key=n.key,
                    message=f"node type '{n.type}' is not enabled in the current phase",
                )
            )

    # 4) 边端点存在性 + branch 合法性
    for e in dsl.edges:
        if e.from_ not in nodes:
            errors.append(
                DslError(
                    code=DSL_REF, message=f"edge from unknown node '{e.from_}'", node_key=e.from_
                )
            )
        if e.to not in nodes:
            errors.append(
                DslError(code=DSL_REF, message=f"edge to unknown node '{e.to}'", node_key=e.to)
            )
        if (
            e.branch is not None
            and nodes.get(e.from_) is not None
            and nodes[e.from_].type not in _BRANCH_NODE_TYPES
        ):
            errors.append(
                DslError(
                    code=DSL_BRANCH,
                    node_key=e.from_,
                    message=f"branch edge from non-branch node '{e.from_}'",
                )
            )
        if (
            e.branch is not None
            and nodes.get(e.from_) is not None
            and nodes[e.from_].type == "human_gate"
            and e.branch not in ("approved", "rejected")
        ):
            errors.append(
                DslError(
                    code=DSL_BRANCH,
                    node_key=e.from_,
                    message=f"human_gate branch must be 'approved' or 'rejected', got '{e.branch}'",
                )
            )
        if nodes.get(e.to) is not None and nodes[e.to].type == "start":
            errors.append(
                DslError(
                    code=DSL_VALIDATION,
                    node_key=e.to,
                    message="start node must not have incoming edges",
                )
            )

    # 4.1) 审批只能出现在主图：子图（loop/parallel 体内）无法在挂起后原地续跑，
    #      放进子图会导致裁决后整段子图重跑（重复副作用）。编译期就拒绝。
    for key, node in _nested_nodes(dsl):
        if node.type == "human_gate":
            errors.append(
                DslError(
                    code=DSL_VALIDATION,
                    node_key=node.key,
                    message=(
                        f"human_gate is only allowed in the top-level graph (found inside '{key}')"
                    ),
                )
            )

    # 4.2) EXPRESSION 审批人：文法必须在发布期判定。
    #      运行期由控制面按候选用户求值（user/space 属性来自租户体系，引擎侧拿不到），
    #      所以这里只做「能否被 GateExpression 解析」的静态校验；放过去的下场是
    #      「发布成功 → 挂起后审批人快照为空 → 审批永远卡住」，属于最难排查的一类。
    for n in dsl.nodes:
        if n.type == "human_gate" and n.config.approvers.type == "EXPRESSION":
            errors.extend(_approver_expression_errors(n))

    # 5) start/end 数量
    starts = [n.key for n in dsl.nodes if n.type == "start"]
    ends = [n.key for n in dsl.nodes if n.type == "end"]
    if len(starts) != 1:
        errors.append(
            DslError(
                code=DSL_VALIDATION, message=f"exactly one start node required, got {len(starts)}"
            )
        )
    if not ends:
        errors.append(DslError(code=DSL_VALIDATION, message="at least one end node required"))

    # 6) 环检测（Kahn）
    if not any(e.code == DSL_REF for e in errors):
        cycle_nodes = _find_cycle(nodes, dsl)
        if cycle_nodes:
            errors.append(
                DslError(
                    code=DSL_CYCLE,
                    message="cycle detected: " + ", ".join(sorted(cycle_nodes)),
                    node_key=sorted(cycle_nodes)[0],
                )
            )

    # 7) 可达性（从 start BFS）
    if starts and not any(e.code in (DSL_REF, DSL_CYCLE) for e in errors):
        reachable = _reachable_from(starts[0], dsl)
        for key in nodes:
            if key not in reachable:
                errors.append(
                    DslError(
                        code=DSL_ORPHAN,
                        node_key=key,
                        message=f"node '{key}' is unreachable from start",
                    )
                )

    # 8) 变量/节点引用
    if not any(e.code in (DSL_REF, DSL_CYCLE) for e in errors):
        errors.extend(_reference_errors(dsl, nodes))

    if errors:
        return ValidationResult(ok=False, errors=errors)
    return ValidationResult(ok=True, normalized_dsl=dsl.canonical_dump(), checksum=checksum_of(dsl))


def _nested_nodes(dsl: WorkflowDsl) -> list[tuple[str, Any]]:
    """展开 loop.body / parallel.branches 里的子图节点（带父节点 key 便于定位）。"""
    out: list[tuple[str, Any]] = []
    for node in dsl.nodes:
        if node.type == "loop":
            out.extend((node.key, n) for n in node.config.body.nodes)
        elif node.type == "parallel":
            for name, branch in node.config.branches.items():
                out.extend((f"{node.key}.{name}", n) for n in branch.nodes)
    return out


# ---- EXPRESSION 审批人：与 Java 侧 GateExpression 保持同一文法子集 ----
# 两边必须一致：发布期在引擎校验（这里），运行期在控制面求值（用户属性在租户库里）。
# 只允许能静态判定的最小集合，避免「发布通过、审批人解析为空」这种死锁。
_APPROVER_SCOPES: dict[str, set[str]] = {
    "user": {"id", "role", "rank", "platformAdmin"},
    "space": {"id"},
}
_COMPARE_OPS = (ast.Eq, ast.NotEq, ast.Lt, ast.LtE, ast.Gt, ast.GtE, ast.In, ast.NotIn)

# 三处同文法：`true` / `false` / `null` 是字面量写法。Python 的 ast 会把它们解析成
# Name（Python 自己的字面量是 True/False/None），若不在这里认，就会出现「画布放行、
# Java 侧能求值、发布期却被拒」的割裂。
_LITERAL_NAMES = {"true": True, "false": False, "null": None, "none": None}


def _expr_problem(node: ast.AST) -> str | None:
    """返回第一个不符合子集的原因；None 表示合法。"""
    if isinstance(node, ast.BoolOp):
        if not isinstance(node.op, (ast.And, ast.Or)):
            return "only 'and' / 'or' are supported"
        for value in node.values:
            problem = _expr_problem(value)
            if problem:
                return problem
        return None
    if isinstance(node, ast.UnaryOp):
        if not isinstance(node.op, ast.Not):
            return "only 'not' is supported as a unary operator"
        return _expr_problem(node.operand)
    if isinstance(node, ast.Compare):
        for op in node.ops:
            if not isinstance(op, _COMPARE_OPS):
                return f"comparison '{type(op).__name__}' is not supported"
        problem = _expr_problem(node.left)
        if problem:
            return problem
        for comparator in node.comparators:
            problem = _expr_problem(comparator)
            if problem:
                return problem
        return None
    if isinstance(node, ast.Constant):
        if isinstance(node.value, str | int | bool) or node.value is None:
            return None
        return "only string / integer / boolean / null literals are allowed"
    if isinstance(node, ast.List):
        for item in node.elts:
            problem = _expr_problem(item)
            if problem:
                return problem
        return None
    if isinstance(node, ast.Attribute):
        base = node.value
        if not isinstance(base, ast.Name) or base.id not in _APPROVER_SCOPES:
            return "attribute base must be 'user' or 'space'"
        allowed = _APPROVER_SCOPES[base.id]
        if node.attr not in allowed:
            return f"unknown attribute {base.id}.{node.attr} (allowed: {sorted(allowed)})"
        return None
    if isinstance(node, ast.Name):
        if node.id.lower() in _LITERAL_NAMES:
            return None
        return f"unknown identifier '{node.id}' (allowed: user / space / true / false / null)"
    return f"syntax '{type(node).__name__}' is not supported"


def _approver_expression_errors(node: Any) -> list[DslError]:
    """EXPRESSION 审批人必须能在发布期解析成受支持的小语言。"""
    expression = (node.config.approvers.expression or "").strip()
    if not expression:
        return [
            DslError(
                code=DSL_VALIDATION,
                node_key=node.key,
                message="human_gate approvers.expression is required when type is EXPRESSION",
            )
        ]
    try:
        parsed = ast.parse(expression, mode="eval")
    except SyntaxError as exc:
        return [
            DslError(
                code=DSL_VALIDATION,
                node_key=node.key,
                message=f"approvers.expression is not parseable: {exc.msg}",
            )
        ]
    problem = _expr_problem(parsed.body)
    if problem:
        return [
            DslError(
                code=DSL_VALIDATION,
                node_key=node.key,
                message=f"approvers.expression not supported: {problem}",
            )
        ]
    return []


def _reference_errors(dsl: WorkflowDsl, nodes: dict[str, Any]) -> list[DslError]:
    """变量/节点引用检查（作用域感知）。
    loop.body 与 parallel.branches 是独立子图：子图内可见「外层已声明变量 + 自己的迭代变量」，
    节点引用既可以是子图内的上游节点，也可以是外层节点的上游。按顶层作用域一刀切会把合法子图
    判成未声明——在 loop 里写 `vars.item` 会直接发布不了。这里递归下钻，按各自作用域判。
    """
    errors = _graph_ref_errors(
        nodes,
        list(dsl.edges),
        declared=set(dsl.variables),
        extra_vars=set(),
        outer_nodes=set(),
        outer_ancestors=set(),
    )
    seen: set[tuple[str, str | None, str]] = set()
    unique: list[DslError] = []
    for err in errors:
        marker = (err.code, err.node_key, err.message)
        if marker in seen:
            continue
        seen.add(marker)
        unique.append(err)
    return unique


def _written_vars(node: Any) -> set[str]:
    """节点运行期会写入的变量名（transform 的 target、loop 的 collect.into）。"""
    out: set[str] = set()
    if node.type == "transform":
        out.update(m.target.removeprefix("vars.") for m in node.config.mappings)
    elif node.type == "loop" and node.config.collect is not None:
        out.add(node.config.collect.into)
    return out


def _visible_vars(
    declared: set[str],
    ancestors: dict[str, set[str]],
    written: dict[str, set[str]],
    key: str,
) -> set[str]:
    """本节点可见变量 = 声明变量 + 上游节点写入的变量。"""
    extra: set[str] = set()
    for anc in ancestors.get(key, set()):
        extra |= written.get(anc, set())
    return declared | extra


def _graph_ref_errors(
    graph_nodes: dict[str, Any],
    graph_edges: list[Any],
    *,
    declared: set[str],
    extra_vars: set[str],
    outer_nodes: set[str],
    outer_ancestors: set[str],
    depth: int = 0,
) -> list[DslError]:
    errors: list[DslError] = []
    ancestors = _ancestors_of(graph_nodes, graph_edges)
    written = {k: _written_vars(n) for k, n in graph_nodes.items()}
    for key, node in graph_nodes.items():
        cfg = node.config.canonical_dump()
        children: list[tuple[Any, set[str]]] = []
        if node.type == "loop":
            cfg.pop("body", None)
            extra = {node.config.item_var, node.config.index_var}
            children.append((node.config.body, extra))
        elif node.type == "parallel":
            cfg.pop("branches", None)
            children.extend((branch, set()) for branch in node.config.branches.values())

        cfg = _strip_destinations(node.type, cfg)
        visible = _visible_vars(declared, ancestors, written, key)

        for text in _collect_strings(cfg):
            errors.extend(
                _scan_text(
                    key,
                    text,
                    local_nodes=graph_nodes,
                    local_ancestors=ancestors,
                    declared=visible,
                    extra_vars=extra_vars,
                    outer_nodes=outer_nodes,
                    outer_ancestors=outer_ancestors,
                )
            )

        for sub, extra in children:
            # 与前端、画布同一口径：超过最大层数的嵌套明确报 422，而不是无限
            # 递归下去（坏数据能把发布请求拖死）或静默跳过校验。
            if depth >= MAX_SUBGRAPH_DEPTH:
                errors.append(
                    DslError(
                        code=DSL_VALIDATION,
                        node_key=key,
                        message=f"subgraph nesting exceeds {MAX_SUBGRAPH_DEPTH} levels",
                    )
                )
                continue
            # 引擎运行时把子图按 WorkflowDsl 解析（nodes 至少 2 个）。只有 1 个节点的
            # 子图发布能过、跑起来必定在分支里炸成 PARALLEL-BRANCH-FAILED，必须提前拦。
            if len(sub.nodes) < 2:
                errors.append(
                    DslError(
                        code=DSL_VALIDATION,
                        node_key=key,
                        message=(
                            "subgraph needs at least 2 nodes "
                            "(a single-node branch cannot be executed)"
                        ),
                    )
                )
            sub_nodes = {n.key: n for n in sub.nodes}
            sub_edges = list(sub.edges)
            errors.extend(_nested_edge_errors(sub_nodes, sub_edges))
            errors.extend(
                _graph_ref_errors(
                    sub_nodes,
                    sub_edges,
                    declared=visible,
                    extra_vars=extra_vars | extra,
                    outer_nodes=outer_nodes | set(graph_nodes),
                    outer_ancestors=outer_ancestors | ancestors.get(key, set()),
                    depth=depth + 1,
                )
            )
    return errors


def _strip_destinations(node_type: str, cfg: dict[str, Any]) -> dict[str, Any]:
    """去掉「写入型」字段再做引用检查。

    transform 的 `mappings[].target`（vars.x）与 loop 的 `collect.into` 是变量定义而不是引用：
    把它们当引用会让"先算一个临时变量"必须先在工作流变量里声明一遍，纯属自找麻烦。
    """
    if node_type == "transform":
        mappings = cfg.get("mappings")
        if isinstance(mappings, list):
            cfg["mappings"] = [
                {"template": (m or {}).get("template", "")} if isinstance(m, dict) else m
                for m in mappings
            ]
    elif node_type == "loop":
        collect = cfg.get("collect")
        if isinstance(collect, dict):
            cfg["collect"] = {k: v for k, v in collect.items() if k != "into"}
    return cfg


def _scan_text(
    key: str,
    text: str,
    *,
    local_nodes: dict[str, Any],
    local_ancestors: dict[str, set[str]],
    declared: set[str],
    extra_vars: set[str],
    outer_nodes: set[str],
    outer_ancestors: set[str],
) -> list[DslError]:
    errors: list[DslError] = []
    for var in _VAR_REF.findall(text):
        if var not in declared and var not in extra_vars:
            errors.append(
                DslError(code=DSL_REF, node_key=key, message=f"undefined variable 'vars.{var}'")
            )
    for ref in _NODE_REF.findall(text):
        if ref in local_nodes:
            if ref == key or ref not in local_ancestors.get(key, set()):
                errors.append(
                    DslError(
                        code=DSL_REF,
                        node_key=key,
                        message=f"node '{ref}' is not an upstream ancestor of '{key}'",
                    )
                )
        elif ref in outer_ancestors:
            continue
        elif ref in outer_nodes:
            errors.append(
                DslError(
                    code=DSL_REF,
                    node_key=key,
                    message=f"node '{ref}' is not an upstream ancestor of '{key}'",
                )
            )
        else:
            errors.append(
                DslError(code=DSL_REF, node_key=key, message=f"reference to unknown node '{ref}'")
            )
    return errors


def _nested_edge_errors(graph_nodes: dict[str, Any], edges: list[Any]) -> list[DslError]:
    """子图内的边规则：端点存在、branch 只能从分支节点出发、start 不得有入边。

    顶层边在 validate_dsl 主体里已查过；子图的边此前完全没查，条件节点放进 loop 后
    写错 branch 会一路跑到运行期才炸。
    """
    errors: list[DslError] = []
    for e in edges:
        if e.from_ not in graph_nodes:
            errors.append(
                DslError(
                    code=DSL_REF,
                    message=f"edge from unknown node '{e.from_}'",
                    node_key=e.from_,
                )
            )
        if e.to not in graph_nodes:
            errors.append(
                DslError(code=DSL_REF, message=f"edge to unknown node '{e.to}'", node_key=e.to)
            )
        src = graph_nodes.get(e.from_)
        if e.branch is not None and src is not None and src.type not in _BRANCH_NODE_TYPES:
            errors.append(
                DslError(
                    code=DSL_BRANCH,
                    node_key=e.from_,
                    message=f"branch edge from non-branch node '{e.from_}'",
                )
            )
        if (
            e.branch is not None
            and src is not None
            and src.type == "human_gate"
            and e.branch not in ("approved", "rejected")
        ):
            errors.append(
                DslError(
                    code=DSL_BRANCH,
                    node_key=e.from_,
                    message=f"human_gate branch must be 'approved' or 'rejected', got '{e.branch}'",
                )
            )
        dst = graph_nodes.get(e.to)
        if dst is not None and dst.type == "start":
            errors.append(
                DslError(
                    code=DSL_VALIDATION,
                    node_key=e.to,
                    message="start node must not have incoming edges",
                )
            )

    # 子图环检测（与顶层同一 Kahn 实现）：发布期就拦下，避免运行期误报 NODE-INTERNAL-001。
    cycle = _find_cycle(graph_nodes, edges=edges)
    if cycle:
        errors.append(
            DslError(
                code=DSL_CYCLE,
                node_key=sorted(cycle)[0],
                message="cycle in subgraph: " + ", ".join(sorted(cycle)),
            )
        )
    return errors


def _ancestors_of(graph_nodes: dict[str, Any], edges: list[Any]) -> dict[str, set[str]]:
    """任意图上的祖先集合（拓扑序传播）。"""
    in_edges: dict[str, list[str]] = {k: [] for k in graph_nodes}
    out_edges: dict[str, list[str]] = {k: [] for k in graph_nodes}
    for e in edges:
        if e.from_ in graph_nodes and e.to in graph_nodes:
            in_edges[e.to].append(e.from_)
            out_edges[e.from_].append(e.to)
    in_degree = {k: len(v) for k, v in in_edges.items()}
    queue = [k for k, d in in_degree.items() if d == 0]
    ancestors: dict[str, set[str]] = {k: set() for k in graph_nodes}
    while queue:
        cur = queue.pop()
        for nxt in out_edges[cur]:
            ancestors[nxt] |= ancestors[cur] | {cur}
            in_degree[nxt] -= 1
            if in_degree[nxt] == 0:
                queue.append(nxt)
    return ancestors


def _find_cycle(
    nodes: dict[str, Any],
    dsl: WorkflowDsl | None = None,
    *,
    edges: list[Any] | None = None,
) -> set[str]:
    """任意边集上的 Kahn 环检测；顶层传 dsl，子图传具名 edges。

    子图（loop.body / parallel.branches）此前不查环，含环子图能过发布校验，
    到运行期 compile_dag 才抛裸 ValueError 并被误归类为 NODE-INTERNAL-001。
    """
    if edges is None:
        edges = list(dsl.edges) if dsl is not None else []
    in_degree = {k: 0 for k in nodes}
    out: dict[str, list[str]] = {k: [] for k in nodes}
    for e in edges:
        if e.from_ in nodes and e.to in nodes:
            in_degree[e.to] += 1
            out[e.from_].append(e.to)
    queue = [k for k, d in in_degree.items() if d == 0]
    visited = 0
    while queue:
        cur = queue.pop()
        visited += 1
        for nxt in out[cur]:
            in_degree[nxt] -= 1
            if in_degree[nxt] == 0:
                queue.append(nxt)
    if visited == len(nodes):
        return set()
    return {k for k, d in in_degree.items() if d > 0}


def _reachable_from(start: str, dsl: WorkflowDsl) -> set[str]:
    out: dict[str, list[str]] = {}
    for e in dsl.edges:
        out.setdefault(e.from_, []).append(e.to)
    seen: set[str] = set()
    stack = [start]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(out.get(cur, []))
    return seen
