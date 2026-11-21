-- CON-6：平台管理员按事件类型过滤审计（WHERE seq < ? AND event_type = ?）原先只能
-- 走 idx_ae_tenant 或全表扫描。加 (event_type, seq) 联合索引，让「按类型翻页」直接命中，
-- 同时保留 seq 用于 cursor 分页。
-- 与其它 ALTER 迁移一致，重复执行保护由 Flyway 的 flyway_schema_history 版本记录保证。
ALTER TABLE audit_event ADD KEY idx_ae_type (event_type, seq);
