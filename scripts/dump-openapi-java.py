"""抓取四个 Java 服务的 /v3/api-docs，规范化后写入 docs/openapi/java-*.json。

运行前置：scripts/start-dev.* 已起全栈，或 CI 里已把四个服务拉起来（端口见 SERVICES）。
用法：
    python scripts/dump-openapi-java.py            # 刷新基线
    python scripts/dump-openapi-java.py --check   # 只比对，不一致就 exit 1（CI 用）

为什么需要规范化：springdoc 直接吐出的 spec 里 `servers` 是「按本次请求 Host 生成的
部署地址」（127.0.0.1:8084 之类），不是 API 契约，换个端口或域名就会让基线整片变红；
`info.title` / `info.version` 默认是 "OpenAPI definition" / "v0"，看不出属于哪个服务、
跟哪一版代码。这里把前者去掉、后者改成服务名与根 pom 版本，再按固定键序序列化——
同一份代码在任何机器上生成出来的字节都一样。

只依赖标准库：CI 的 python-ci / e2e 镜像不用为此装东西。
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "openapi"

# 顺序固定，输出文件名也固定；端口可用各服务自己的环境变量覆盖。
SERVICES = (
    ("agent-gateway", "AF_GATEWAY_PORT", 8080),
    ("workflow-service", "AF_WORKFLOW_PORT", 8084),
    ("audit-service", "AF_AUDIT_PORT", 8086),
    ("connector-service", "AF_CONNECTOR_PORT", 8085),
)


def project_version() -> str:
    pom = (ROOT / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<version>([0-9][^<]*)</version>", pom)
    if not match:
        raise RuntimeError("无法从根 pom.xml 读到项目版本")
    return match.group(1).strip()


def fetch(port: int) -> dict:
    url = f"http://127.0.0.1:{port}/v3/api-docs"
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}")
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"{url} 不可用：{exc}") from exc


def normalize(spec: dict, service: str, version: str) -> str:
    spec.pop("servers", None)
    info = spec.get("info") if isinstance(spec.get("info"), dict) else {}
    info["title"] = service
    info["version"] = version
    spec["info"] = info
    return json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只比对不写文件")
    args = parser.parse_args()

    version = project_version()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failures: list[str] = []

    for service, env_name, default_port in SERVICES:
        port = int(os.environ.get(env_name, default_port))
        path = OUT_DIR / f"java-{service}.json"
        try:
            generated = normalize(fetch(port), service, version)
        except RuntimeError as exc:
            failures.append(f"{service}: {exc}")
            continue

        if not args.check:
            path.write_text(generated, encoding="utf-8")
            print(f"wrote {path.relative_to(ROOT)}")
            continue

        if not path.exists():
            failures.append(f"{service}: 基线文件不存在 {path.relative_to(ROOT)}")
            continue
        committed = path.read_text(encoding="utf-8")
        if committed == generated:
            print(f"ok  {path.relative_to(ROOT)}")
            continue
        diff = difflib.unified_diff(
            committed.splitlines(),
            generated.splitlines(),
            fromfile=f"committed/{path.name}",
            tofile=f"generated/{path.name}",
            lineterm="",
        )
        failures.append(f"{service}: 契约漂移\n" + "\n".join(list(diff)[:40]))

    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Java OpenAPI 基线一致" if args.check else "Java OpenAPI 基线已刷新")
    return 0


if __name__ == "__main__":
    sys.exit(main())
