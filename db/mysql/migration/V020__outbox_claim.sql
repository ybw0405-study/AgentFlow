-- 审计 outbox 的领取标记。
--
-- 原设计依赖 due() 的 FOR UPDATE SKIP LOCKED 实现多副本互斥，但 relay() 没有
-- @Transactional：autocommit 下 SELECT 结束即释放行锁，等于没加锁，多副本会
-- 捞到同一批重复投递。
--
-- 修复采用两段式：短事务内「捞取 + claim 成 PROCESSING」，事务外做网络投递，
-- 完成后 markSent / markRetry 把 PROCESSING 改回终态/PENDING；崩溃残留的
-- PROCESSING 由 reclaimExpired 按 lease 收回。网络调用不进事务，避免长事务。
--
-- AF-10：MODIFY 与两处 DDL 均按幂等守卫写法，已迁移库重放不报重复列/键。

ALTER TABLE audit_outbox
  MODIFY status ENUM('PENDING','PROCESSING','SENT','DEAD') NOT NULL DEFAULT 'PENDING';

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_outbox'
             AND COLUMN_NAME = 'claimed_at'),
    'DO 0',
    'ALTER TABLE audit_outbox ADD COLUMN claimed_at DATETIME(3) NULL'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_outbox'
             AND INDEX_NAME = 'idx_ao_claim'),
    'DO 0',
    'ALTER TABLE audit_outbox ADD KEY idx_ao_claim (status, claimed_at)'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
