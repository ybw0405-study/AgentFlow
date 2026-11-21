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
-- DROP + CREATE 让脚本可独立重复执行；DELIMITER 块与 V002/V004 写法一致。
DROP TRIGGER IF EXISTS trg_wv_no_update;

DELIMITER $$
CREATE TRIGGER trg_wv_no_update BEFORE UPDATE ON workflow_version FOR EACH ROW
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
