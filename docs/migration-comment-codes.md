# 迁移注释中的历史决策代号索引

Flyway 已应用迁移的 checksum 覆盖文件全文（含注释），改注释会让既有库启动报
`Migration checksum mismatch`。因此 `db/mysql/migration/*.sql` 注释里保留的历史决策代号
**不随正文清理改写**，统一在本索引解释；源码与文档中的同类代号已改写为自解释表述
（架构决策表 D1–D11 见 [architecture.md](architecture.md)）。

| 代号 | 含义 | 出现位置 |
|---|---|---|
| AF-10 | MySQL 迁移幂等守卫约定：MySQL 8 无 `ADD COLUMN / ADD KEY IF NOT EXISTS`，用 information_schema 条件守卫包裹 DDL，使已迁移库重放直接跳过（范式见 V008 注释） | V008、V012–V020 注释 |

新增迁移请勿再引入决策代号：注释直接写行为与约束。
