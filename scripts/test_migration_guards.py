""" 守卫脚本的静态回归用例（不需真实 MySQL）。

运行：engine-api/.venv/Scripts/python.exe -m pytest scripts/test_migration_guards.py -q
真实重放由三个脚本自己（带 MySQL）负责，这里只钉住「守卫本身会不会失灵」。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

import check_cross_table_consistency as consistency  # noqa: E402
import check_migration_table_options as table_opts  # noqa: E402
import check_migrations_idempotent as idem  # noqa: E402


# ---- CREATE TABLE 选项守卫 ----


def test_table_options_accepts_compliant_ddl():
    ddl = """
CREATE TABLE t (
  id CHAR(36) NOT NULL,
  name VARCHAR(128) GENERATED ALWAYS AS (IF(x IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"""
    assert table_opts.check(ddl) == []
    assert [name for name, _ in table_opts.table_options(ddl)] == ["t"]


@pytest.mark.parametrize(
    "bad",
    [
        "CREATE TABLE t (id CHAR(36));",  # 三项全缺
        "CREATE TABLE t (id CHAR(36)) ENGINE=InnoDB;",  # 缺 CHARSET/COLLATE
        "CREATE TABLE t (id CHAR(36)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;",  # 缺 COLLATE
    ],
)
def test_table_options_rejects_missing_options(bad):
    assert table_opts.check(bad), "缺 ENGINE/CHARSET/COLLATE 的建表必须被拦住"


def test_table_options_ignores_comment_noise_and_pure_alter_files():
    text = "-- CREATE TABLE ghost (x INT);\nALTER TABLE real_t ADD COLUMN a INT;"
    assert table_opts.check(text) == []
    assert table_opts.table_options(text) == []


def test_all_real_migrations_pass_table_options_guard():
    assert table_opts.check_dir() == []
    total = sum(
        len(table_opts.table_options(p.read_text(encoding="utf-8")))
        for p in sorted((ROOT / "db/mysql/migration").glob("V*.sql"))
   )
    assert total == 28, "迁移表数量变化时必须同步复核守卫与 grants 对账"


# ---- 幂等静态审计 ----


def test_static_audit_passes_on_real_migrations():
    assert idem.static_audit() == []


def test_static_audit_detects_unguarded_add_column_and_index():
    # 用临时目录替换迁移目录，验证守卫不是「恒真断言」
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "V001__base.sql").write_text(
            "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
            "COLLATE=utf8mb4_unicode_ci;",
            encoding="utf-8",
       )
        (d / "V002__bad.sql").write_text(
            "ALTER TABLE t ADD COLUMN c INT;", encoding="utf-8"
       )
        original = idem.MIGRATION_DIR
        idem.MIGRATION_DIR = d
        try:
            errors = idem.static_audit()
        finally:
            idem.MIGRATION_DIR = original
        assert any("ADD COLUMN 缺少" in e for e in errors)


def test_static_audit_rejects_drop_before_create_trigger():
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        d = Path(tmp)
        (d / "V001__base.sql").write_text(
            "CREATE TABLE t (id INT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 "
            "COLLATE=utf8mb4_unicode_ci;",
            encoding="utf-8",
       )
        (d / "V002__bad.sql").write_text(
            "DROP TRIGGER IF EXISTS trg;\n"
            "CREATE TRIGGER IF NOT EXISTS trg BEFORE UPDATE ON t FOR EACH ROW BEGIN SET NEW.id=1; END;",
            encoding="utf-8",
       )
        original = idem.MIGRATION_DIR
        idem.MIGRATION_DIR = d
        try:
            errors = idem.static_audit()
        finally:
            idem.MIGRATION_DIR = original
        assert any("保护空窗" in e for e in errors)


def test_migrations_are_version_ordered_and_increments_detected():
    files = idem.migration_files()
    assert len(files) == 19
    assert files[0].name.startswith("V001") and files[-1].name.startswith("V019")
    increments = [p for p in files if idem.is_increment(p)]
    assert len(increments) == 9
    assert "V019__immutable_version_columns.sql" in {p.name for p in increments}


def test_v019_creates_before_dropping_and_uses_if_not_exists():
    sql = (ROOT / "db/mysql/migration/V019__immutable_version_columns.sql").read_text(
        encoding="utf-8"
   )
    assert sql.index("CREATE TRIGGER IF NOT EXISTS trg_wv_no_update_af10") < sql.index(
        "DROP TRIGGER IF EXISTS trg_wv_no_update"
   ), "V019 必须「先建后删」，否则重现保护空窗"


# ---- 跨表一致性查询生成 ----


def test_orphan_query_wellformed():
    q = consistency.orphan_query(
        ("node_execution", "execution_id", "workflow_execution", "id")
   )
    assert "LEFT JOIN" in q and "IS NULL" in q
    assert "`node_execution`" in q and "`workflow_execution`" in q


def test_reference_map_is_complete_and_parent_key_is_id():
    assert len(consistency.REFERENCES) == 13
    assert all(len(r) == 4 and r[3] == "id" for r in consistency.REFERENCES)
    assert "DELETE_RULE" in consistency.fk_query()


def test_guard_scripts_self_tests_do_not_raise():
    table_opts.self_test()
    idem.self_test()
    consistency.self_test()
