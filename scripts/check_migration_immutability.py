#!/usr/bin/env python3
"""迁移不可变门禁：已发布 tag 中存在的迁移文件不得在 HEAD 被修改。

Flyway 已应用的迁移文件必须不可变；改动一律新增 V+1。本脚本对比最近一个发布 tag
与当前 HEAD，若某个迁移文件在 tag 时已存在、且在 tag..HEAD 间被修改，则失败。
新增文件（tag 时不存在）允许，因为它们是合法的新迁移。

本地：python scripts/check_migration_immutability.py
CI：由 .github/workflows/config-guard.yml 调用。
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

MIGRATION_DIR = "db/mysql/migration"


def _run(argv: list[str]) -> str:
    return subprocess.run(argv, capture_output=True, text=True, check=False).stdout.strip()


def latest_tag() -> str | None:
    out = _run(["git", "tag", "--list", "v*", "--sort=-version:refname"])
    tags = [t for t in out.splitlines() if t]
    return tags[0] if tags else None


def files_at(ref: str) -> set[str]:
    out = _run(["git", "ls-tree", "-r", "--name-only", ref, "--", MIGRATION_DIR])
    return {p for p in out.splitlines() if p}


def main() -> int:
    tag = latest_tag()
    if tag is None:
        print("check_migration_immutability: 未找到任何 v* tag，跳过（仓库尚未发布）")
        return 0

    tag_files = files_at(tag)
    head_files = files_at("HEAD")

    modified = _run(
        ["git", "diff", "--name-only", f"{tag}..HEAD", "--", MIGRATION_DIR]
    ).splitlines()
    modified = [m for m in modified if m]

    # 仅在 tag 时已存在的文件被改才算违规；tag 之后新增的文件是合法新迁移。
    violations = [m for m in modified if m in tag_files]

    if not violations:
        print(f"check_migration_immutability: 通过（基准 tag={tag}，{len(head_files - tag_files)} 个新迁移）")
        return 0

    print(f"check_migration_immutability: 失败（基准 tag={tag}）", file=sys.stderr)
    print("以下已发布迁移文件被修改（违反 Flyway 不可变纪律，请改用新增 V+1）：", file=sys.stderr)
    for v in sorted(violations):
        print(f" - {v}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
