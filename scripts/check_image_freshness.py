#!/usr/bin/env python3
"""镜像-源码新鲜度预检：防止用过期本地镜像跑新源码导致 Flyway checksum 冲突。

问题：workflow-service 的 jar 内嵌了 db/mysql/migration（pom.xml resources）。
若本地镜像构建在迁移文件改动之前，jar 内的迁移副本与挂载目录不一致，
启动时内嵌 Flyway 与 infra flyway 容器校验和冲突、服务 exit 1。

本脚本对比 workflow-service 镜像创建时间与 db/mysql/migration 最新文件 mtime：
- 镜像比迁移文件旧 → 警告并建议 `docker compose build`（默认仅警告，不阻断）。
- 镜像不存在或 docker 不可用 → 跳过（compose 会按需构建）。

本地：python scripts/check_image_freshness.py
CI：不调用（CI 总是从源码构建）。
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATION_DIR = ROOT / "db" / "mysql" / "migration"
IMAGE = os.environ.get("AF_WORKFLOW_IMAGE", "ghcr.io/ybw0405-study/agentflow-workflow-service:0.6.0")


def _run(argv: list[str]) -> str:
    return subprocess.run(argv, capture_output=True, text=True, check=False).stdout.strip()


def image_created_epoch() -> float | None:
    out = _run(["docker", "image", "inspect", IMAGE, "--format", "{{.Created}}"])
    if not out:
        return None
    # Created 形如 2026-11-24T13:33:25.891381632Z
    from datetime import datetime
    try:
        return datetime.fromisoformat(out.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def newest_migration_mtime() -> float:
    if not MIGRATION_DIR.is_dir():
        return 0.0
    return max((f.stat().st_mtime for f in MIGRATION_DIR.glob("*.sql")), default=0.0)


def main() -> int:
    created = image_created_epoch()
    if created is None:
        print(f"check_image_freshness: 镜像 {IMAGE} 不存在或 docker 不可用，compose 将按需构建（跳过）")
        return 0
    newest = newest_migration_mtime()
    if newest > created:
        from datetime import datetime
        img_time = datetime.fromtimestamp(created).strftime("%Y-%m-%d %H:%M")
        print(
            f"check_image_freshness: 警告 — workflow-service 镜像构建于 {img_time}，"
            f"但 db/mysql/migration 有更新的文件。用旧镜像跑新源码会触发 Flyway checksum 冲突。\n"
            f"  请先执行：docker compose -f ops/docker-compose.infra.yml -f ops/docker-compose.app.yml build\n"
            f"  （或 AF_IMAGE_TAG 指向已与源码同步的发布 tag）",
            file=sys.stderr,
        )
        return 2  # 非零但仅作警告；start-dev 可据此提示
    print("check_image_freshness: 通过（镜像不早于最新迁移文件）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
