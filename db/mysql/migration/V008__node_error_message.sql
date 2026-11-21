-- 节点失败原因落行。
--
-- 引擎的 NodeFailed 事件本来就有 message（截断 500 字，例如
-- "branch 'kb' failed: DSL-REF-001 ..."），但早前只投影 errorCode，
-- 界面上只能看到 PARALLEL-BRANCH-FAILED 这类代码，排查必须回事件流翻。
-- 这里把原因存到行上，执行详情与节点树直接可见。
--
-- AF-10：ADD COLUMN 幂等。MySQL 8 无 `ADD COLUMN IF NOT EXISTS`，用
-- information_schema 判定 + PREPARE 动态执行；已迁移库重放走 DO 0 跳过，
-- 不再报 Duplicate column name。守卫对 SQL 语义零影响（列定义原文保留）。
SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'node_execution'
             AND COLUMN_NAME = 'error_message'),
    'DO 0',
    'ALTER TABLE node_execution ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
