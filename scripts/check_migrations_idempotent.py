""" 幂等门禁：空库 + 已迁移库各重放一次。

两阶段：
  1) 静态审计（零依赖，永远执行）：增量迁移里的 ADD COLUMN / ADD KEY /
     CREATE INDEX 必须带 information_schema 守卫；CREATE TRIGGER 必须
     IF NOT EXISTS；V019 的 DROP TRIGGER 必须排在 CREATE 之后（先建后删）。
  2) 真实重放（需要 MySQL）：
       * af_idem_fresh —— 按版本序把全部 V*.sql 灌一遍（空库重放一次）；
       * af_idem_migrated —— 先灌一遍成为「已迁移库」，再把不含 CREATE TABLE
         的增量迁移重放一次（已迁移库重放一次），两遍都必须 0 错误。
     MySQL 由以下任一方式提供：
       * AF_MYSQL_CMD：读 stdin 的 mysql 客户端命令前缀，
         例如 "mysql -h127.0.0.1 -P3306 -uroot -psecret"
         （脚本自动追加 --database=<db>）。
       * 否则若本机有 docker：自动起一个临时 mysql:8.4 容器（用完即删）。
       * 否则只跑静态审计并打印 SKIP 提示。

用法（CI 建议加 --require-replay，防止「忘了提供 MySQL」时静态审计蒙混过关）：
    python scripts/check_migrations_idempotent.py
    python scripts/check_migrations_idempotent.py --require-replay
    python scripts/check_migrations_idempotent.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATION_DIR = ROOT / "db" / "mysql" / "migration"
PASSWORD = "afidem"
FRESH_DB = "af_idem_fresh"
MIGRATED_DB = "af_idem_migrated"


def migration_files() -> list[Path]:
    def version(p: Path) -> int:
        m = re.match(r"V(\d+)__", p.name)
        return int(m.group(1)) if m else 10**9

    return sorted(MIGRATION_DIR.glob("V*.sql"), key=version)


def strip_comments(text: str) -> str:
    return "\n".join(line.split("--", 1)[0] for line in text.splitlines())


def is_increment(path: Path) -> bool:
    """不含 CREATE TABLE 的迁移 = 增量迁移，是重放门禁的对象。"""
    return not re.search(r"CREATE\s+TABLE", strip_comments(path.read_text(encoding="utf-8")), re.I)


def static_audit() -> list[str]:
    """返回违规说明；空列表表示增量迁移已全部具备幂等守卫。"""
    errors: list[str] = []
    files = migration_files()
    if not files:
        return ["未找到任何 V*.sql 迁移"]
    increments = [p for p in files if is_increment(p)]
    if not increments:
        return ["未找到任何增量迁移（不含 CREATE TABLE），检查判定逻辑"]
    for path in increments:
        sql = strip_comments(path.read_text(encoding="utf-8")).upper()
        if "ADD COLUMN" in sql and "INFORMATION_SCHEMA.COLUMNS" not in sql:
            errors.append(f"{path.name}: ADD COLUMN 缺少 information_schema.COLUMNS 守卫")
        if ("ADD KEY" in sql or "CREATE INDEX" in sql) and "INFORMATION_SCHEMA.STATISTICS" not in sql:
            errors.append(f"{path.name}: ADD KEY/CREATE INDEX 缺少 information_schema.STATISTICS 守卫")
        if "CREATE TRIGGER" in sql and "IF NOT EXISTS" not in sql:
            errors.append(f"{path.name}: CREATE TRIGGER 缺少 IF NOT EXISTS")
        # V019 的 DROP 必须排在 CREATE 之后，否则会重现保护空窗
        if "DROP TRIGGER" in sql:
            create_at = sql.find("CREATE TRIGGER")
            drop_at = sql.find("DROP TRIGGER")
            if create_at < 0 or drop_at < create_at:
                errors.append(f"{path.name}: DROP TRIGGER 未排在 CREATE TRIGGER 之后（保护空窗）")
    return errors


def self_test() -> None:
    guarded = """
SET @af_ddl = (SELECT IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_NAME='t' AND COLUMN_NAME='c'), 'DO 0', 'ALTER TABLE t ADD COLUMN c INT'));
PREPARE af_stmt FROM @af_ddl; EXECUTE af_stmt; DEALLOCATE PREPARE af_stmt;
"""
    unguarded = "ALTER TABLE t ADD COLUMN c INT;"
    assert "INFORMATION_SCHEMA.COLUMNS" in guarded.upper()
    assert "INFORMATION_SCHEMA.COLUMNS" not in unguarded.upper()
    assert not re.search(r"CREATE\s+TABLE", "ALTER TABLE t ADD COLUMN c INT;", re.I)
    assert re.search(r"CREATE\s+TABLE", "CREATE TABLE t (id INT);", re.I)
    print("self-test: 4 cases passed")


class Executor:
    """把 SQL 文本通过 stdin 喂给 mysql 客户端。"""

    def __init__(self, argv: list[str], container: str | None = None) -> None:
        self.argv = argv
        self.container = container

    def _run(self, args: list[str], sql: str) -> None:
        result = subprocess.run(
            args, input=sql, text=True, capture_output=True, encoding="utf-8"
       )
        if result.returncode:
            noisy = "\n".join(
                line for line in (result.stderr or "").splitlines()
                if "Using a password" not in line
           )
            raise RuntimeError(f"SQL 执行失败 (rc={result.returncode}): {noisy.strip()}")

    def admin(self, sql: str) -> None:
        self._run(self.argv, sql)

    def db(self, name: str, sql: str) -> None:
        self._run(self.argv + [f"--database={name}"], sql)

    def query(self, name: str, sql: str) -> str:
        """执行查询并返回 -N -B 的机器可读输出（供一致性校验复用）。"""
        out = subprocess.run(
            self.argv + [f"--database={name}", "-N", "-B", "-e", sql],
            text=True, capture_output=True, encoding="utf-8",
       )
        if out.returncode:
            raise RuntimeError(f"查询失败 (rc={out.returncode}): {out.stderr.strip()}")
        return out.stdout

    def cleanup(self) -> None:
        if self.container:
            subprocess.run(
                ["docker", "rm", "-f", self.container],
                capture_output=True, check=False,
           )


def build_executor() -> Executor | None:
    override = os.environ.get("AF_MYSQL_CMD")
    if override:
        return Executor(shlex.split(override))
    if not shutil.which("docker"):
        return None
    cid = subprocess.check_output(
        [
            "docker", "run", "-d", "--rm",
            "-e", f"MYSQL_ROOT_PASSWORD={PASSWORD}",
            "-e", "MYSQL_ROOT_HOST=%",
            "mysql:8.4", "--default-time-zone=+08:00",
        ],
        text=True,
   ).strip()
    argv = ["docker", "exec", "-i", cid, "mysql", "-uroot", f"-p{PASSWORD}"]
    executor = Executor(argv, container=cid)
    # 官方 mysql 镜像初始化时先起一个 --skip-grant-tables 的临时实例，
    # 此时 mysqladmin ping 会假阳性（root 密码尚未生效），紧接着执行业务 SQL
    # 必然报 1045 Access denied。用「带密码的真实查询」作为就绪判据。
    for _ in range(60):
        ready = subprocess.run(
            argv + ["-N", "-B", "-e", "SELECT 1"],
            capture_output=True, text=True,
       )
        if ready.returncode == 0 and ready.stdout.strip() == "1":
            return executor
        time.sleep(2)
    executor.cleanup()
    raise RuntimeError("临时 MySQL 容器在 120s 内未就绪（带密码查询未通过）")


def replay(executor: Executor, db: str, files: list[Path]) -> None:
    for path in files:
        executor.db(db, path.read_text(encoding="utf-8"))


def verify_schema(executor: Executor, db: str) -> None:
    """重放结束后抽查关键不变量：表齐、索引齐、不可变触发器仍在且是新定义。"""
    probe = """
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE') AS tables,
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME='idx_ae_type') AS ae_type_idx,
  (SELECT COUNT(*) FROM information_schema.TRIGGERS
    WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_wv_no_update_af10'
      AND ACTION_STATEMENT LIKE '%published_at%') AS immutable_trg,
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_COLLATION <> 'utf8mb4_unicode_ci') AS wrong_collation;
"""
    tables, ae_idx, trg, wrong = (int(x) for x in executor.query(db, probe).strip().split("\t"))
    if tables < 20 or ae_idx != 1 or trg != 1 or wrong != 0:
        raise RuntimeError(
            f"schema 不变量不满足: tables={tables} idx_ae_type={ae_idx} "
            f"immutable_trigger={trg} wrong_collation={wrong}"
       )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--require-replay", action="store_true",
                    help="无 MySQL 时也判定失败（CI 用）")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return 0

    audit_errors = static_audit()
    if audit_errors:
        print(" 静态审计 FAIL:")
        for err in audit_errors:
            print(" - " + err)
        return 1
    print(f" 静态审计 PASS：{len([p for p in migration_files() if is_increment(p)])} 份增量迁移均带幂等守卫")

    files = migration_files()
    increments = [p for p in files if is_increment(p)]
    executor = None
    try:
        executor = build_executor()
    except RuntimeError as exc:
        print(f" 真实重放无法启动 MySQL: {exc}")
        if args.require_replay:
            return 1
        print(" 真实重放 SKIP（本机无 docker 且未设 AF_MYSQL_CMD）")
        return 0
    if executor is None:
        print(" 真实重放 SKIP（本机无 docker 且未设 AF_MYSQL_CMD）")
        return 1 if args.require_replay else 0

    try:
        # 先清库，保证脚本可重复运行（同一 MySQL 实例上多次重跑不残留）
        executor.admin(
            f"DROP DATABASE IF EXISTS {FRESH_DB}; "
            f"DROP DATABASE IF EXISTS {MIGRATED_DB}; "
            f"CREATE DATABASE {FRESH_DB} "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; "
            f"CREATE DATABASE {MIGRATED_DB} "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
       )
        replay(executor, FRESH_DB, files) # 空库重放一次
        print(f" 空库重放 PASS：{len(files)} 份迁移全部成功")
        replay(executor, MIGRATED_DB, files) # 先成为已迁移库
        replay(executor, MIGRATED_DB, increments) # 已迁移库再重放一次
        print(f" 已迁移库重放 PASS：{len(increments)} 份增量迁移二次执行 0 错误")
        verify_schema(executor, MIGRATED_DB)
        print(" schema 不变量 PASS：表/索引/不可变触发器/统一 collation")
        return 0
    except RuntimeError as exc:
        print(f" 真实重放 FAIL: {exc}")
        return 1
    finally:
        executor.cleanup()


if __name__ == "__main__":
    sys.exit(main())
