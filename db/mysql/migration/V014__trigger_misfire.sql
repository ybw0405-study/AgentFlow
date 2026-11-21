-- 错过档位（misfire）的处置策略：不同的定时任务对「停机期间漏掉的档」期待完全相反。
--   每分钟探活：补 180 次毫无意义，应当跳过；
--   每小时对账：只补最近一次会漏掉 4 个小时的账，应当全部补齐；
--   每日站会提醒：下午才补出来的提醒是噪音，应当跳过。
-- 默认沿用 V012 的行为（只补最近一档），升级不改既有触发器的语义。
--
-- 最近一次扫描的结局：FIRE（真的跑了）/ SKIP（这一批档位被放弃）。
-- 与 last_fired_at 分开：跳过不是「触发过」，界面不能把它写成「上次触发」。
--
-- AF-10：两个 ADD COLUMN 各自幂等守卫（见 V008 注释）。字符串默认值用 '' 转义，
-- PREPARE 执行时还原成 DEFAULT 'CATCH_UP_LATEST'，语义与原文一致。
SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_trigger'
             AND COLUMN_NAME = 'misfire_policy'),
    'DO 0',
    'ALTER TABLE workflow_trigger ADD COLUMN misfire_policy VARCHAR(24) NOT NULL DEFAULT ''CATCH_UP_LATEST'' AFTER enabled'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;

SET @af_ddl = (
  SELECT IF(
    EXISTS(SELECT 1 FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'workflow_trigger'
             AND COLUMN_NAME = 'last_outcome'),
    'DO 0',
    'ALTER TABLE workflow_trigger ADD COLUMN last_outcome VARCHAR(16) NULL AFTER last_slot_at'));
PREPARE af_stmt FROM @af_ddl;
EXECUTE af_stmt;
DEALLOCATE PREPARE af_stmt;
