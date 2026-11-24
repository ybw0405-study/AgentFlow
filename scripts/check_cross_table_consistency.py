""" 迁移后跨表引用一致性校验。

方案给了两条路：补 FK ON DELETE RESTRICT，或产出迁移后一致性校验脚本。
本仓库选后者（脚本路径），理由：现有数据可能已有孤儿行，直接在存量库上
补 FK 会 ALTER 失败；且软删父行时 FK 的 RESTRICT 语义与业务预期需要单独评审。
脚本在每次迁移后跑一遍，把「软删/部分成功产生孤儿行」变成 CI 可拦截的门禁。

检查内容：
  * 逐条 REFERENCES 跑 LEFT JOIN 孤儿计数，任一 > 0 即 FAIL；
  * 列出已有 DB 级外键及其 DELETE_RULE，断言已有 FK 不是 CASCADE/SET NULL
    （会静默扩大删除面），并如实报告哪些引用仍只有普通索引、无 FK 兜底。

用法：
    AF_MYSQL_CMD="mysql -h127.0.0.1 -uroot -psecret" \
    AF_MYSQL_DATABASE=agentflow \
      python scripts/check_cross_table_consistency.py
    python scripts/check_cross_table_consistency.py --self-test
    python scripts/check_cross_table_consistency.py --require-db   # CI 强制真库
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_migrations_idempotent import build_executor, migration_files  # noqa: E402

# (子表, 子列, 父表, 父列)。与 db/mysql/migration 的列定义逐条核对过。
REFERENCES: list[tuple[str, str, str, str]] = [
    ("workflow_execution", "definition_id", "workflow_definition", "id"),
    ("node_execution", "execution_id", "workflow_execution", "id"),
    ("gate_task", "execution_id", "workflow_execution", "id"),
    ("gate_resolve_outbox", "execution_id", "workflow_execution", "id"),
    ("tool_credential", "connector_id", "tool_connector", "id"),
    ("knowledge_document", "kb_id", "knowledge_base", "id"),
    ("knowledge_chunk", "kb_id", "knowledge_base", "id"),
    ("knowledge_chunk", "document_id", "knowledge_document", "id"),
    ("eval_case", "dataset_id", "eval_dataset", "id"),
    ("eval_run", "dataset_id", "eval_dataset", "id"),
    ("eval_case_result", "run_id", "eval_run", "id"),
    ("eval_case_result", "case_id", "eval_case", "id"),
    ("ab_experiment", "dataset_id", "eval_dataset", "id"),
]


def orphan_query(ref: tuple[str, str, str, str]) -> str:
    child, col, parent, parent_col = ref
    return (
        f"SELECT COUNT(*) FROM `{child}` c "
        f"LEFT JOIN `{parent}` p ON c.`{col}` = p.`{parent_col}` "
        f"WHERE c.`{col}` IS NOT NULL AND p.`{parent_col}` IS NULL"
   )


def fk_query() -> str:
    return (
        "SELECT kcu.CONSTRAINT_NAME, kcu.TABLE_NAME, kcu.COLUMN_NAME, "
        "       kcu.REFERENCED_TABLE_NAME, rc.DELETE_RULE "
        "FROM information_schema.KEY_COLUMN_USAGE kcu "
        "JOIN information_schema.REFERENTIAL_CONSTRAINTS rc "
        "  ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA "
        " AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME "
        "WHERE kcu.TABLE_SCHEMA = DATABASE() "
        "  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL "
        "ORDER BY kcu.TABLE_NAME, kcu.COLUMN_NAME"
   )


def self_test() -> None:
    q = orphan_query(("node_execution", "execution_id", "workflow_execution", "id"))
    assert "LEFT JOIN" in q and "IS NULL" in q
    assert "node_execution" in q and "workflow_execution" in q
    assert len(REFERENCES) >= 10
    assert all(len(r) == 4 and r[3] == "id" for r in REFERENCES)
    assert "DELETE_RULE" in fk_query()
    print("self-test: 5 cases passed")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--require-db", action="store_true",
                    help="无 MySQL 时也判定失败（CI 用）")
    ap.add_argument("--bootstrap-migrations", action="store_true",
                    help="在临时真库上先灌入全部 V*.sql 再校验（CI / 本机自测用）")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        self_test()
        return 0

    db = os.environ.get("AF_MYSQL_DATABASE", "agentflow")
    executor = None
    try:
        executor = build_executor()
    except RuntimeError as exc:
        print(f" 无法连接 MySQL: {exc}")
        if args.require_db:
            return 1
        print(" 真库校验 SKIP（本机无 docker 且未设 AF_MYSQL_CMD）")
        return 0
    if executor is None:
        print(" 真库校验 SKIP（本机无 docker 且未设 AF_MYSQL_CMD）")
        return 1 if args.require_db else 0
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", db):
        print(f" FAIL: 非法数据库名 {db!r}")
        return 1

    try:
        if args.bootstrap_migrations:
            # 独立真跑时没有「已迁移库」可连，先建库灌全部迁移再校验。
            # 表名/列名写错会直接在这一步或下面的孤儿查询里报错，不会静默放过。
            executor.admin(
                f"DROP DATABASE IF EXISTS `{db}`; "
                f"CREATE DATABASE `{db}` "
                "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
           )
            files = migration_files()
            for path in files:
                executor.db(db, path.read_text(encoding="utf-8"))
            print(f" 真库 {db} 已灌入 {len(files)} 份迁移")
        orphans: list[str] = []
        for ref in REFERENCES:
            count = int(executor.query(db, orphan_query(ref)).strip() or "0")
            if count:
                orphans.append(f"{ref[0]}.{ref[1]} -> {ref[2]}.{ref[3]}: {count} 行孤儿")
        if orphans:
            print(" 一致性校验 FAIL（存在孤儿行）:")
            for line in orphans:
                print("  - " + line)
            return 1

        fk_rows = [
            line.split("\t")
            for line in executor.query(db, fk_query()).strip().splitlines()
            if line.strip()
        ]
        bad_rules = [r for r in fk_rows if len(r) >= 5 and r[4] in ("CASCADE", "SET NULL")]
        if bad_rules:
            print(" FAIL：已有 FK 的 DELETE_RULE 会静默扩大删除面:")
            for r in bad_rules:
                print("  - " + "\t".join(r))
            return 1

        fk_pairs = {(r[1], r[2]) for r in fk_rows if len(r) >= 5}
        no_fk = [f"{c}.{col}" for c, col, _, _ in REFERENCES if (c, col) not in fk_pairs]
        print(f" PASS：{len(REFERENCES)} 条跨表引用 0 孤儿；"
              f"已有 {len(fk_rows)} 条 DB 级 FK 且 DELETE_RULE 安全")
        print(f" 提示：{len(no_fk)} 条引用仍无 DB 级 FK（靠本门禁兜底）: "
              + ", ".join(no_fk))
        return 0
    except RuntimeError as exc:
        print(f" FAIL: {exc}")
        return 1
    finally:
        executor.cleanup()


if __name__ == "__main__":
    sys.exit(main())
