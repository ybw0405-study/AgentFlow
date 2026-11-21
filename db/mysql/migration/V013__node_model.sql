-- V013 节点实际使用的模型：用量按模型分解需要知道「这个节点是谁跑的」。
-- 只记真实下发的模型名（provider 可能把占位名回落到默认模型），不记节点请求的模型；
-- 非 LLM 节点与输出被溢出（D5）的节点留空，界面上归到「未标注」。
--
-- AF-10：ADD COLUMN 幂等守卫（见 V008 注释），已迁移库重放直接跳过。
SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'node_execution'
             AND COLUMN_NAME = 'model'),
    'DO 0',
    'ALTER TABLE node_execution ADD COLUMN model VARCHAR(128) NULL AFTER latency_ms'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
