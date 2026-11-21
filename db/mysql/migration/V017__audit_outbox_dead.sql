-- 审计 outbox 的隔离区。
--
-- 原设计「status 只有 PENDING / SENT、重投次数无上限」保证事件不丢，却带来两个
-- 反效果：降级直写与 outbox 同库，库只读、权限不足或连接池耗尽时，每行会封顶
-- 60s 一直重试到永远，既不收敛也没有告警；载荷反序列化失败则写一条只剩 id /
-- tenant / type 的骨架后 markSent，完整 message_json 被永久跳过却记为已送达。
--
-- 引入 DEAD：不再无限自动重投，但绝不删除——原始 message_json 原样保留，管理端
-- 可列出、可重放。语义上仍不是「放弃投递」，只是把「自动重试」换成「人工介入」。
--
-- AF-10：MODIFY 本身幂等（重复执行是同定义重写）；两列一索引各自加守卫，
-- 已迁移库重放不报 Duplicate column / Duplicate key name。
ALTER TABLE audit_outbox
  MODIFY status ENUM('PENDING','SENT','DEAD') NOT NULL DEFAULT 'PENDING';

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_outbox'
             AND COLUMN_NAME = 'dead_at'),
    'DO 0',
    'ALTER TABLE audit_outbox ADD COLUMN dead_at DATETIME(3) NULL'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_outbox'
             AND COLUMN_NAME = 'dead_reason'),
    'DO 0',
    'ALTER TABLE audit_outbox ADD COLUMN dead_reason VARCHAR(500) NULL'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_outbox'
             AND INDEX_NAME = 'idx_ao_dead'),
    'DO 0',
    'ALTER TABLE audit_outbox ADD KEY idx_ao_dead (status, dead_at)'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
