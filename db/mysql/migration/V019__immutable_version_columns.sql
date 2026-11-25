-- PERF-7：workflow_version 的不可变触发器（V002）原先只护住
-- dsl_json / version_no / dsl_checksum / definition_id，published_at / published_by /
-- immutable 本身仍能被 UPDATE：
--   * 把 immutable 改成 0 就能关掉整条触发器保护，再任意改 dsl_json；
--   * 重写 published_at / published_by 就能篡改「谁在什么时候首次发布」。
-- 应用层 rollback 特意绕开 markPublished 来保发布历史，这里把这层意图固化到 DB。
--
-- 关键取舍：markPublished 会把这两列从 NULL 一次性写成值（合法写入），所以只在
-- OLD 值已非空时禁止改动；immutable 一旦为 1 则任何改动都不允许。这样既挡住
-- 「改元数据/关保护」，又不破坏首次发布。
--
-- AF-10：消除「DROP TRIGGER 成功 → CREATE TRIGGER 失败 → 不可变保护永久丢失」的空窗。
-- DDL 隐式提交，MySQL 无法把 DROP+CREATE 放进事务。改为「先建后删」：
--   1) 以新名字 trg_wv_no_update_af10 建目标定义（CREATE TRIGGER IF NOT EXISTS，
--      MySQL 8.4 支持；同一表同一事件允许多个 BEFORE UPDATE 触发器）。此刻 V002 的
--      旧触发器仍在，任何失败都不会失去保护 —— 首次应用零空窗。
--   2) 再删掉 V002 的旧定义。此时新触发器已是唯一且更强的保护。
-- 已迁移库重放：步骤 1 命中 IF NOT EXISTS 跳过，步骤 2 无对象可删，全程不触碰
-- 现有保护。回滚与「CREATE 失败的应急恢复」见 scripts/rollback/。
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS trg_wv_no_update_af10 BEFORE UPDATE ON workflow_version
FOR EACH ROW
BEGIN
  IF OLD.immutable = 1 AND (
       NOT (NEW.dsl_json <=> OLD.dsl_json)
       OR NEW.version_no <> OLD.version_no
       OR NEW.dsl_checksum <> OLD.dsl_checksum
       OR NEW.definition_id <> OLD.definition_id
       OR NEW.immutable <> OLD.immutable
       OR (OLD.published_at IS NOT NULL AND NOT (NEW.published_at <=> OLD.published_at))
       OR (OLD.published_by IS NOT NULL AND NOT (NEW.published_by <=> OLD.published_by))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
  END IF;
END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_wv_no_update;
