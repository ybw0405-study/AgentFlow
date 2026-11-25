"""最小授权守卫：迁移脚本的 CREATE TABLE 集合必须与最小授权脚本的 GRANT 集合一致。

为什么需要它
------------
least-privilege.sql 是「迁移完成后执行」的手工脚本，历史上只覆盖到 V004 的 12 张表，
V005~V017 新增的表一条 GRANT 都没有——照它部署，连接器/审批/评测/知识库/触发器/
审计 outbox 全部 permission denied，审计还会静默丢失。这类漂移纯靠人肉同步必然再犯。

这里做纯文本解析（零依赖、秒级、跨 Windows/Linux）：
  * 扫描 db/mysql/migration/V*.sql 的所有 CREATE TABLE，得到「应有表集合」
  * 扫描 db/mysql/grants/least-privilege.sql 的所有 GRANT，得到「已授权表集合」
  * 双向比对：漏授权、授权了不存在的表，一律失败
  * 额外校验权限语义（防止「补了表名但给错权限」这种更隐蔽的漂移）：
      - audit_event 只追加：任何账号都只能 SELECT/INSERT，禁止 UPDATE/DELETE
      - outbox 表需要全 DML：relay 要改状态、清已投递行，失败降级还要 INSERT
      - 其余业务表：af_app 必须全 DML

用法：
    python scripts/check-grants.py # 比对，任何不一致 exit 1；全部一致 exit 0

CI 接入（.github/ 由主审维护，此处给出可直接粘贴的步骤）：
    # java-ci.yml / 任一常驻 job 的 steps 中，检出代码后加一步即可，无需起 MySQL：
    - name: Check DB grants coverage
      run: python scripts/check-grants.py
    # 或在 scripts/verify-all.sh 的 java 段之前加：
    # python "$ROOT/scripts/check-grants.py" || FAILED+=('db-grants')
    # 该脚本只用标准库，不依赖 runner 预装任何第三方包。

只依赖标准库：与 check-java-routes.py 一样可在任何 python3 环境直接跑。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_DIR = ROOT / "db" / "mysql" / "migration"
GRANTS_FILE = ROOT / "db" / "mysql" / "grants" / "least-privilege.sql"

# CREATE TABLE [IF NOT EXISTS] <name> —— 表名可带反引号，也可带 schema 前缀
CREATE_TABLE_RE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:`?\w+`?\.)?`?(\w+)`?",
    re.IGNORECASE,
)
# GRANT <privs> ON [schema.]<table> TO '<account>'@'<host>'
GRANT_RE = re.compile(
    r"GRANT\s+(.+?)\s+ON\s+(?:`?\w+`?\.)?`?(\w+)`?\s+TO\s+'([^']+)'@'[^']*'\s*;",
    re.IGNORECASE,
)

FULL_DML = {"SELECT", "INSERT", "UPDATE", "DELETE"}
APPEND_ONLY = {"SELECT", "INSERT"}


def migration_tables() -> dict[str, str]:
    """返回 {表名: 来源文件}，忽略只有 ALTER TABLE / CREATE TRIGGER 的迁移。"""
    found: dict[str, str] = {}
    for path in sorted(MIGRATION_DIR.glob("V*.sql")):
        text = path.read_text(encoding="utf-8")
        for name in CREATE_TABLE_RE.findall(text):
            found[name.lower()] = path.name
    return found


def grant_entries() -> dict[str, dict[str, set[str]]]:
    """返回 {表名: {账号: 权限集合}}；同名表多行 GRANT 的权限做并集。"""
    entries: dict[str, dict[str, set[str]]] = {}
    text = GRANTS_FILE.read_text(encoding="utf-8")
    for privs, table, account in GRANT_RE.findall(text):
        priv_set = {p.strip().upper() for p in privs.split(",") if p.strip()}
        by_account = entries.setdefault(table.lower(), {})
        by_account.setdefault(account, set()).update(priv_set)
    return entries


def main() -> int:
    errors: list[str] = []

    tables = migration_tables()
    if not tables:
        errors.append(f"未在 {MIGRATION_DIR} 找到任何 CREATE TABLE，检查路径/正则是否有误")
    grants = grant_entries()
    if not grants:
        errors.append(f"未在 {GRANTS_FILE} 找到任何 GRANT，检查路径/正则是否有误")

    table_names = set(tables)
    granted_names = set(grants)

    # 1) 每个迁移表都要被授权（核心：防止「部署即坏」）
    for name in sorted(table_names - granted_names):
        errors.append(f"[漏授权] 迁移表 {name}（{tables[name]}）没有对应的 GRANT")

    # 2) 不给不存在的表授权（防止迁移回滚后遗留脏授权）
    for name in sorted(granted_names - table_names):
        errors.append(f"[多余授权] GRANT 引用了不存在的表 {name}")

    # 3) 权限语义校验（只对两边都存在的表做，避免连锁噪声）
    for name in sorted(table_names & granted_names):
        by_account = grants[name]
        if name == "audit_event":
            # 审计只追加：任何账号都不能有写/删权限，且必须能被读/追加
            for account, privs in by_account.items():
                if privs != APPEND_ONLY:
                    errors.append(
                        f"[权限越界] {account}@{name} 应仅 {sorted(APPEND_ONLY)}，实为 {sorted(privs)}"
                    )
            continue
        # 其余表（含 outbox）：只应授权给 af_app，且必须是全 DML
        # —— outbox 的 relay 要改状态/清行、失败降级要 INSERT，缺一不可
        if "af_app" not in by_account:
            errors.append(f"[缺账号] 表 {name} 未授权给业务账号 af_app")
        for account, privs in by_account.items():
            if account != "af_app":
                errors.append(f"[账号越界] 业务表 {name} 不应授权给 {account}")
            elif privs != FULL_DML:
                errors.append(
                    f"[权限不足] {account}@{name} 应含全 DML {sorted(FULL_DML)}，实为 {sorted(privs)}"
                )

    if errors:
        print(f"FAIL: least-privilege.sql 与迁移不一致（{len(errors)} 项）", file=sys.stderr)
        for e in errors:
            print(f" - {e}", file=sys.stderr)
        print(
            " 修法：按迁移版本补齐/修正 db/mysql/grants/least-privilege.sql 的 GRANT。",
            file=sys.stderr,
        )
        return 1

    print(
        f"OK: {len(table_names)} 张迁移表全部有对应 GRANT，权限语义符合最小授权策略 "
        f"(audit_event 只追加 / outbox 全 DML / 其余业务表 af_app 全 DML)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
