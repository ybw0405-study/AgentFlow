-- 单个工作流同时进行的执行数上限。
--
-- 0 表示「不限」：升级后既有工作流的行为完全不变，是否限流由使用方显式打开。
-- 限的是「正在占用引擎的执行」（PENDING / RUNNING）；挂起等人工审批的不占，
-- 否则一个等人的审批就能把整条流水线堵死。
--
-- 并发判定要按 (definition_id, status) 数在跑的执行。
-- 现有 idx_we_def 只有 (definition_id, seq)，数状态还得回表逐行过滤。
--
-- AF-10：ADD COLUMN 与 CREATE INDEX 各自幂等守卫（见 V008 / V015 注释），
-- 已迁移库重放不报 Duplicate column / Duplicate key name。
SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_definition'
             AND COLUMN_NAME = 'max_concurrent'),
    'DO 0',
    'ALTER TABLE workflow_definition ADD COLUMN max_concurrent INT NOT NULL DEFAULT 0 AFTER status'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_execution'
             AND INDEX_NAME = 'idx_we_def_status'),
    'DO 0',
    'CREATE INDEX idx_we_def_status ON workflow_execution (definition_id, status)'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
