"""极简迁移执行器：按文件名序应用 db/postgres/migration/V*.sql，记录于 schema_migrations。

规则同 Flyway：已应用的迁移禁止修改（校验已应用版本的字符级哈希，漂移即拒绝启动）。
"""

from __future__ import annotations

import hashlib
import logging
import re
from dataclasses import dataclass
from pathlib import Path

import asyncpg

logger = logging.getLogger(__name__)
_FILE_RE = re.compile(r"^V(\d+)__[\w-]+\.sql$")


@dataclass(frozen=True)
class MigrationFile:
    version: int
    filename: str
    sql: str
    checksum: str


def load_migrations(migrations_path: str) -> list[MigrationFile]:
    """同步加载迁移文件（启动期一次性 IO，由调用方在进入事件循环前/后均可调用）。"""
    base = Path(migrations_path)
    if not base.is_dir():
        raise FileNotFoundError(f"PG migrations dir not found: {base.resolve()}")
    out: list[MigrationFile] = []
    for f in base.iterdir():
        m = _FILE_RE.match(f.name)
        if not m:
            continue
        out.append(
            MigrationFile(
                version=int(m.group(1)),
                filename=f.name,
                sql=f.read_text(encoding="utf-8"),
                checksum=hashlib.sha256(f.read_bytes()).hexdigest(),
            )
        )
    out.sort(key=lambda m: m.version)
    if not out:
        raise FileNotFoundError(f"no migrations under {base.resolve()}")
    return out


async def run_migrations(pool: asyncpg.Pool, migrations_path: str) -> None:
    migrations = load_migrations(migrations_path)
    async with pool.acquire() as conn:
        await conn.execute(
            "CREATE TABLE IF NOT EXISTS schema_migrations ("
            " version INT PRIMARY KEY,"
            " filename TEXT NOT NULL,"
            " checksum TEXT NOT NULL,"
            " applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"
        )
        rows = await conn.fetch("SELECT version, checksum FROM schema_migrations")
        applied = {r["version"]: r["checksum"] for r in rows}

        for m in migrations:
            if m.version in applied:
                if applied[m.version] != m.checksum:
                    raise RuntimeError(
                        f"migration {m.filename} was modified after being applied — forbidden"
                    )
                continue
            async with conn.transaction():
                await conn.execute(m.sql)
                await conn.execute(
                    "INSERT INTO schema_migrations (version, filename, checksum)"
                    " VALUES ($1, $2, $3)",
                    m.version,
                    m.filename,
                    m.checksum,
                )
            logger.info("applied PG migration %s", m.filename)
