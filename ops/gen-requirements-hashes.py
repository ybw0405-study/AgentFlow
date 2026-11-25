#!/usr/bin/env python3
"""为 engine-api/requirements.lock.txt 补真实 --hash（G15，可复现脚本）。

用法（仓库根目录）：
    python ops/gen-requirements-hashes.py # 重新生成带 hash 的锁文件
    python ops/gen-requirements-hashes.py --check # 只校验现有锁文件，不写回

哈希来源：https://pypi.org/pypi/<name>/<version>/json 的 urls[].digests.sha256，
覆盖该版本在 PyPI 上的**全部**发行文件（各平台 wheel + sdist），
与 pip-compile --generate-hashes 等价。取不到即报错退出，**绝不编造 hash**。

脚本幂等：会先剥离已有的 --hash 行与 -e . 行再重新生成。
CI 安装方式（见 .github/workflows/python-ci.yml）：
    pip install --require-hashes -r requirements.lock.txt
    pip install --no-deps -e .
--require-hashes 不接受 editable 需求，故项目本体单独以 --no-deps -e . 安装。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "engine-api" / "requirements.lock.txt"
PIN_RE = re.compile(r"^([A-Za-z0-9_.\-]+)\s*==\s*([^\s;]+)\s*(;.*)?$")

HEADER = """# AgentFlow engine-api 依赖锁文件（G15：精确版本 + 真实 --hash）
# 由 ops/gen-requirements-hashes.py 生成；hash 取自 PyPI JSON API，
# 覆盖对应版本的全部发行文件。请勿手工编辑；重新生成：
# python ops/gen-requirements-hashes.py
# CI 用 `pip install --require-hashes -r requirements.lock.txt` 离线可复现安装，
# 项目本体再以 `pip install --no-deps -e .` 单独安装（--require-hashes 不接受 -e）。
# 校验：python ops/gen-requirements-hashes.py --check
"""


def fetch_hashes(name: str, version: str) -> list[str]:
    url = f"https://pypi.org/pypi/{name}/{version}/json"
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            data = json.load(response)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError(f"无法获取 {name}=={version} 的 PyPI 元数据：{exc}") from exc
    hashes = sorted({
        item["digests"]["sha256"]
        for item in data.get("urls", [])
        if "sha256" in item.get("digests", {})
    })
    if not hashes:
        raise RuntimeError(f"{name}=={version} 在 PyPI 上没有 sha256 记录，拒绝生成无 hash 的锁行")
    return hashes


def parse_pins(text: str) -> list[tuple[str, str, str]]:
    pins: list[tuple[str, str, str]] = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip() if not raw.lstrip().startswith("#") else ""
        line = line.rstrip("\\ \t")
        if not line or line.startswith("-e ") or line.startswith("--hash") or line.startswith("-r "):
            continue
        match = PIN_RE.match(line)
        if not match:
            raise RuntimeError(f"无法解析锁文件行（拒绝静默跳过）：{raw!r}")
        pins.append((match.group(1), match.group(2), (match.group(3) or "").strip()))
    if not pins:
        raise RuntimeError("锁文件中没有解析到任何 name==version 精确 pin")
    return pins


def render(pins: list[tuple[str, str, str]]) -> str:
    lines = [HEADER.rstrip(), ""]
    for name, version, marker in pins:
        hashes = fetch_hashes(name, version)
        suffix = f" {marker}" if marker else ""
        lines.append(f"{name}=={version}{suffix} \\")
        for index, digest in enumerate(hashes):
            tail = " \\" if index < len(hashes) - 1 else ""
            lines.append(f" --hash=sha256:{digest}{tail}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="只校验，不写回")
    args = parser.parse_args()

    original = LOCK.read_text(encoding="utf-8")
    pins = parse_pins(original)
    print(f"解析到 {len(pins)} 个精确 pin，开始联网取真实 sha256 ...")
    rendered = render(pins)
    if args.check:
        if rendered.rstrip() != original.rstrip():
            print("锁文件与生成结果不一致，请重新运行生成脚本。", file=sys.stderr)
            return 1
        print("check OK：锁文件哈希与 PyPI 一致。")
        return 0
    LOCK.write_text(rendered, encoding="utf-8")
    print(f"wrote {LOCK}（{len(pins)} pins，全部带真实 PyPI sha256）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
