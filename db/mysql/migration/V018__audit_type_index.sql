-- CON-6：平台管理员按事件类型过滤审计（WHERE seq < ? AND event_type = ?）原先只能
-- 走 idx_ae_tenant 或全表扫描。加 (event_type, seq) 联合索引，让「按类型翻页」直接命中，
-- 同时保留 seq 用于 cursor 分页。
--
-- AF-10：ADD KEY 幂等守卫。此前依赖「Flyway 版本记录保证不重复执行」，但迁移失败
-- 回滚后手工重放、或运维误把同一脚本再灌一次，都会报 Duplicate key name。改由
-- information_schema.STATISTICS 判定 + PREPARE 动态执行，空库与已迁移库重放均成功。
SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_event'
             AND INDEX_NAME = 'idx_ae_type'),
    'DO 0',
    'ALTER TABLE audit_event ADD KEY idx_ae_type (event_type, seq)'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
