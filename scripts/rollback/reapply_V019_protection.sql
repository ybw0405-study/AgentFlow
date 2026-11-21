-- 应急：V019 的 CREATE TRIGGER 失败 / 误删保护后，重建目标定义。
-- 与 V019 步骤 1 完全一致（CREATE TRIGGER IF NOT EXISTS，幂等），
-- 且不 DROP 任何东西 —— 无论当前有没有旧触发器，执行它都不会制造保护空窗。
-- 由 scripts/ROLLBACK.md 的「保护丢失」章节引用。
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
