# 迁移回滚与升级 runbook（/ V019）

本轮修改了**已经应用过的**迁移文件（`V001`–`V011` 补 `COLLATE`，`V008`/`V012`–`V018`
改幂等守卫，`V019` 改触发器替换顺序）。在存量库上部署前**必须**按本 runbook 顺序操作，
否则 Flyway 会在启动校验阶段直接拒绝。

## 0. 前置事实（必须知道）

- Flyway 默认 `validateOnMigrate=true`：它把每个已应用迁移文件的 checksum 记在
  `flyway_schema_history`。**改了已应用的 V*.sql，checksum 就会不匹配**，服务启动报
  `Validate failed: Migration checksum mismatch`。
- DDL 隐式提交，MySQL 无法把 `DROP TRIGGER` + `CREATE TRIGGER` 放进一个事务。
  这是 V019 保护空窗的根因，也是必须用「先建后删」顺序而非「加事务」来修的原因。
- `CREATE TRIGGER IF NOT EXISTS` 与「同表同事件多触发器」在 **MySQL 8.4** 上实测可用
  （仓库 `ops/docker-compose.infra.yml` / e2e 固定 `mysql:8.4`）。低于该版本请先升级。
- `ADD COLUMN / ADD KEY / CREATE INDEX IF NOT EXISTS` 在 MySQL 8.4 **不可用**，
  因此幂等守卫用 `information_schema` + `PREPARE` 动态 DDL。

## 1. 存量库升级（正常路径）

```bash
# 1) 停掉所有连库服务（workflow-service / connector-service / audit-service）
# 2) 先修 checksum，再启动服务。Flyway 10 命令（容器或本机 CLI 均可）：
flyway -url=jdbc:mysql://<host>:3306/<db> -user=<user> -password=<pwd> repair
# 3) 核对 repair 只是重算 checksum，没有删掉任何历史版本行：
mysql -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
# 4) 启动服务。V015–V019 的守卫是幂等的，即使被重放也不会报 Duplicate key/column。
```

> `flyway repair` 只重算 checksum、清理失败记录，**不会重放迁移**。若某个迁移此前
> 确实执行失败（history 里 `success=0`），repair 后下次启动会重新执行它 —— 这正是
> 幂等守卫存在的意义。

## 2. 存量表的 collation（的边界，不粉饰）

`V001`–`V011` 补的 `ENGINE/CHARSET/COLLATE` **只影响空库新建的表**。已经存在的表
不会因为改了迁移文件而自动变色。要统一存量表，需单独评估后执行（会重建索引，务必
在维护窗口做，先备份）：

```sql
ALTER TABLE <table> CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

`scripts/check_migration_table_options.py` 守卫的是**迁移文件**（防新增表再漏），
不校验存量表的实际 collation；`scripts/check_migrations_idempotent.py` 的
`verify_schema` 会在它新建的库上断言 28 张表全部为 `utf8mb4_unicode_ci`。

## 3. V019 保护丢失 / 升级失败

症状：`workflow_version` 的 `immutable=1` 行竟然能被改 `dsl_json` / `published_at`。

```bash
# A. 只丢了  版触发器（trg_wv_no_update_af10）：
mysql <db> < scripts/rollback/reapply_V019_protection.sql
# 该文件 CREATE ... IF NOT EXISTS，不 DROP，任何状态下执行都不会扩大空窗。

# B. 需要整体回退到 V002 旧定义：
mysql <db> < scripts/rollback/U019__restore_v002_immutable_trigger.sql
# 先建回原名触发器，再删  版，全程至少一条保护存在。
```

验证保护真的生效（本机 MySQL 8.4 实测口径）：

```sql
-- 首次发布（published_at 从 NULL 写成值）应成功
UPDATE workflow_version SET published_at = NOW(3) WHERE id='<id>';
-- 再次改写 published_at 应报 ERROR 1644 (45000) WF-IMMUTABLE-001
UPDATE workflow_version SET published_at = DATE_ADD(published_at, INTERVAL 1 DAY) WHERE id='<id>';
```

## 4. 幂等门禁（CI 接线后）

| 脚本 | 作用 |
| --- | --- |
| `scripts/check_migrations_idempotent.py` | 空库重放全部 19 份 + 已迁移库重放 9 份增量迁移，断言 0 错误 |
| `scripts/check_migration_table_options.py` | 断言所有 `CREATE TABLE` 带 `ENGINE`+`CHARSET`+`COLLATE` |
| `scripts/check_cross_table_consistency.py` | 迁移后 13 条跨表引用孤儿行计数 + FK `DELETE_RULE` 检查 |
| `scripts/test_migration_guards.py` | 上述脚本的静态回归用例 |

三个门禁脚本都支持 `--self-test`，且不接 MySQL 时只做静态审计并打印 `SKIP`；
CI 请务必加 `--require-replay` / `--require-db`，避免「忘了提供服务」时蒙混过关。
