""" 守卫：所有 CREATE TABLE 必须显式带 ENGINE + CHARSET + COLLATE。

为什么三项都要：
  * ENGINE=InnoDB —— 非 InnoDB 时 ON DUPLICATE KEY UPDATE / 事务 / FK 语义不成立；
  * DEFAULT CHARSET=utf8mb4 —— 不显式声明则继承库默认，跨库部署结果不同；
  * COLLATE=utf8mb4_unicode_ci —— MySQL 8 的 utf8mb4 默认 collation 是
    utf8mb4_0900_ai_ci，与方案里库级 utf8mb4_unicode_ci 不一致；跨表字符串
    JOIN 会报 Illegal mix of collations，或触发隐式转换让索引失效。

用法（CI）：
    python scripts/check_migration_table_options.py           # 门禁，失败 exit 1
    python scripts/check_migration_table_options.py --self-test  # 自带回归用例

零依赖，标准库即可。CI 接线建议见最终报告。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATION_DIR = ROOT / "db" / "mysql" / "migration"

TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?([A-Za-z0-9_]+)`?\s*\(", re.IGNORECASE
)
REQUIRED = ("ENGINE=INNODB", "CHARSET=UTF8MB4", "COLLATE=UTF8MB4_UNICODE_CI")


def strip_line_comments(text: str) -> str:
    """去掉 `--` 行注释，避免注释里的 CREATE TABLE 字样被误判。"""
    return "\n".join(line.split("--", 1)[0] for line in text.splitlines())


def table_options(text: str) -> list[tuple[str, str]]:
    """返回 [(表名, 表选项串)]；用括号配平定位 CREATE TABLE 的闭合 `)`。"""
    clean = strip_line_comments(text)
    out: list[tuple[str, str]] = []
    for match in TABLE_RE.finditer(clean):
        depth = 1
        i = match.end()
        while i < len(clean) and depth:
            if clean[i] == "(":
                depth += 1
            elif clean[i] == ")":
                depth -= 1
            i += 1
        if depth:
            out.append((match.group(1), ""))
            continue
        end = clean.find(";", i)
        options = clean[i : end if end >= 0 else len(clean)]
        out.append((match.group(1), options.upper()))
    return out


def check(text: str) -> list[str]:
    """返回违规说明列表；空列表表示通过。纯 ALTER 迁移（无 CREATE TABLE）合法。"""
    errors: list[str] = []
    tables = table_options(text)
    for name, options in tables:
        missing = [req for req in REQUIRED if req not in options]
        if missing:
            errors.append(f"{name}: 缺少 {', '.join(missing)}（选项串: {options.strip()}）")
    return errors


def check_dir(migration_dir: Path = MIGRATION_DIR) -> list[str]:
    errors: list[str] = []
    files = sorted(migration_dir.glob("V*.sql"))
    if not files:
        return [f"{migration_dir} 下没有 V*.sql"]
    total_tables = 0
    for path in files:
        text = path.read_text(encoding="utf-8")
        total_tables += len(table_options(text))
        for err in check(text):
            errors.append(f"{path.name}: {err}")
    if total_tables == 0:
        errors.append("整个迁移目录未解析到任何 CREATE TABLE，检查路径/正则是否有误")
    return errors


def self_test() -> None:
    good = """
CREATE TABLE t (
  id CHAR(36) NOT NULL,
  name VARCHAR(128) GENERATED ALWAYS AS (IF(x IS NULL, name, NULL)) VIRTUAL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
"""
    bad = "CREATE TABLE t (id CHAR(36) NOT NULL, PRIMARY KEY (id));"
    assert check(good) == [], "合规建表被误判"
    assert check(bad), "缺少 ENGINE/CHARSET/COLLATE 的建表未被拦住"
    assert table_options(good)[0][0] == "t"
    assert len(table_options(good + bad)) == 2
    print("self-test: 2 cases passed")


def main() -> int:
    if "--self-test" in sys.argv:
        self_test()
        return 0
    errors = check_dir()
    if errors:
        print(" 守卫 FAIL:")
        for err in errors:
            print("  - " + err)
        return 1
    tables = [
        name
        for path in sorted(MIGRATION_DIR.glob("V*.sql"))
        for name, _ in table_options(path.read_text(encoding="utf-8"))
    ]
    print(f" 守卫 PASS：{len(tables)} 张表均带 ENGINE+CHARSET+COLLATE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
