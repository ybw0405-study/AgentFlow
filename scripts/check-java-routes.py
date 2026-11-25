"""Java 路由静态粗筛：不启动 Spring，从 Controller 源码比对入库的 OpenAPI 基线。

为什么需要它
------------
四份 docs/openapi/java-*.json 由 springdoc 在**运行时**生成，全量漂移检查只能放在
e2e（要真起基础设施 + 四个服务）里。于是出现一段真空：纯改 Controller 的 PR 若没
触发 e2e，或 e2e 排队/被新 push 取消，就没有任何契约保护；而引擎侧有
contract-drift 这种秒级独立检查，两边不对称。

这里只做正则级粗筛（零依赖、秒级）：
  * 类级 @RequestMapping("/prefix") + 方法级 @GetMapping("/suffix") 拼出全路径
  * 方法级直接写全路径、以及不限方法的 @RequestMapping("/engine/**") 也支持
  * common 模块的共享端点（/health）按「每个服务都可见」处理
  * 与基线 paths/methods 双向比对：源码多、基线多、或出现无法判定的注解写法，一律失败

它只能发现「路由集合变了但没刷基线」。请求/响应 schema、参数类型的漂移仍由 e2e 里的
`dump-openapi-java.py --check`（运行时全量 spec）负责——粗筛与精查是互补关系。

用法：
    python scripts/check-java-routes.py # 比对，不一致 exit 1

只依赖标准库：contract-drift 的镜像不用为此装东西。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "openapi"

# 与 dump-openapi-java.py 的 SERVICES 保持同一组服务名。
SERVICES = (
    "agent-gateway",
    "workflow-service",
    "audit-service",
    "connector-service",
)
# common 里的 HealthController 被四个服务的组件扫描共同收进 spec，粗筛也要带上。
COMMON_SRC = ROOT / "common" / "src" / "main" / "java"

HTTP_METHODS = frozenset(
    {"get", "post", "put", "delete", "patch", "head", "options", "trace"}
)
METHOD_BY_ANNOTATION = {
    "Get": "get",
    "Post": "post",
    "Put": "put",
    "Delete": "delete",
    "Patch": "patch",
}

# mapping 注解有三种合法形态：@GetMapping、@GetMapping()、@GetMapping("/x")。
# 前两种映射到类级前缀本身。仓库里全都是这三种；一旦出现 value = "..."、数组或
# 常量，宁可让 CI 报错，也不能静默漏掉一条路由（那会让粗筛形同虚设）。
MAPPING = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b\s*(\([^)]*\))?")
LITERAL_ARG = re.compile(r'^\(\s*"([^"]+)"\s*\)$')
NO_ARG = re.compile(r"^\(\s*\)$")
# 只匹配「以 class 关键字开头的声明行」，避免把 Javadoc 里的 "This class ..." 当成类声明。
CLASS_DECL = re.compile(
    r"^\s*(?:public\s+)?(?:final\s+|abstract\s+)?class\s+\w+", re.MULTILINE
)
CONTROLLER = re.compile(r"@RestController\b")


def join_path(prefix: str, suffix: str) -> str:
    if not prefix:
        return suffix
    return prefix.rstrip("/") + "/" + suffix.lstrip("/")


def scan_service(service: str) -> tuple[set[tuple[str, str]], set[str], list[str]]:
    """返回 (具体方法路由, 不限方法的路由 path, 无法判定的注解说明)。"""
    roots = [ROOT / service / "src" / "main" / "java", COMMON_SRC]
    files: list[Path] = []
    for root in roots:
        if root.is_dir():
            files.extend(sorted(root.rglob("*.java")))

    specific: set[tuple[str, str]] = set()
    any_paths: set[str] = set()
    problems: list[str] = []

    for file in files:
        text = file.read_text(encoding="utf-8")
        if not CONTROLLER.search(text):
            continue
        rel = file.relative_to(ROOT).as_posix()

        class_match = CLASS_DECL.search(text)
        head = text[: class_match.start()] if class_match else text
        body = text[class_match.start() :] if class_match else text

        def parse(segment: str) -> list[tuple[str, str | None, bool]]:
            """返回 (注解种类, 路径字面量, 无法判定)；路径为 None 表示映射到类级前缀。"""
            out: list[tuple[str, str | None, bool]] = []
            for m in MAPPING.finditer(segment):
                arg = m.group(2)
                if arg is None or NO_ARG.match(arg):
                    out.append((m.group(1), None, False))
                    continue
                literal = LITERAL_ARG.match(arg)
                if literal:
                    out.append((m.group(1), literal.group(1), False))
                else:
                    out.append((m.group(1), None, True))
            return out

        head_ann = parse(head)
        body_ann = parse(body)
        if any(unsupported for _, _, unsupported in head_ann + body_ann):
            problems.append(
                f"{rel}: 存在非字面量的 @*Mapping 写法，静态粗筛无法判定，"
                "请改回字面量或同步刷新基线"
            )

        class_prefixes = [
            path for kind, path, _ in head_ann if kind == "Request" and path is not None
        ]
        if len(class_prefixes) > 1:
            problems.append(
                f"{rel}: 类级 @RequestMapping 声明了多个路径，静态粗筛不支持"
            )
        prefix = class_prefixes[0] if class_prefixes else ""

        for kind, path, unsupported in body_ann:
            if unsupported:
                continue
            full = join_path(prefix, path) if path else (prefix or "/")
            if kind == "Request":
                # 不限定 method 的映射（如网关的 /engine/**）：只比对 path 存在性，
                # 不逐个方法比对，避免把 springdoc 生成的方法集合误判成漂移。
                any_paths.add(full)
            else:
                specific.add((METHOD_BY_ANNOTATION[kind], full))

    return specific, any_paths, problems


def baseline_for(service: str) -> tuple[set[tuple[str, str]], set[str]]:
    path = OUT_DIR / f"java-{service}.json"
    if not path.exists():
        raise RuntimeError(f"基线文件不存在：{path.relative_to(ROOT).as_posix()}")
    spec = json.loads(path.read_text(encoding="utf-8"))
    pairs: set[tuple[str, str]] = set()
    paths: set[str] = set()
    for route, item in (spec.get("paths") or {}).items():
        if not isinstance(item, dict):
            continue
        paths.add(route)
        for method in item:
            if method.lower() in HTTP_METHODS:
                pairs.add((method.lower(), route))
    return pairs, paths


def main() -> int:
    failures: list[str] = []
    total = 0

    for service in SERVICES:
        try:
            base_pairs, base_paths = baseline_for(service)
        except RuntimeError as exc:
            failures.append(f"{service}: {exc}")
            continue

        specific, any_paths, problems = scan_service(service)
        failures.extend(f"{service}: {p}" for p in problems)

        missing = sorted(specific - base_pairs)
        missing_any = sorted(p for p in any_paths if p not in base_paths)
        stale = sorted(
            (m, p)
            for (m, p) in base_pairs
            if (m, p) not in specific and p not in any_paths
        )

        for m, p in missing:
            failures.append(f"{service}: 源码有但基线缺 {m.upper():<6} {p}")
        for p in missing_any:
            failures.append(f"{service}: 源码有但基线缺 ANY {p}")
        for m, p in stale:
            failures.append(f"{service}: 基线有但源码已无 {m.upper():<6} {p}")

        total += len(specific) + len(any_paths)
        if not (missing or missing_any or stale or problems):
            print(
                f"ok {service}: {len(specific)} 条方法路由 + "
                f"{len(any_paths)} 条通配路由与基线一致"
            )

    if failures:
        print("Java 路由与 OpenAPI 基线不一致：", file=sys.stderr)
        print("\n".join(failures), file=sys.stderr)
        print(
            "\n请起全栈后运行 `python scripts/dump-openapi-java.py` 刷新四份基线并入库；\n"
            "e2e 里的运行时 --check 会继续做更细的请求/响应 schema 校验。",
            file=sys.stderr,
        )
        return 1

    print(f"Java 路由粗筛通过（共 {total} 条路由）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
