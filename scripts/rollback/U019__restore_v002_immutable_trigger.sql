-- U019：回滚 V019（版「先建后删」）到 V002 的不可变触发器定义。
--
-- 这不是 Flyway 的 U 迁移（社区版不支持 flyway undo），而是一份手工应急 SQL，
-- 由 scripts/ROLLBACK.md 的 runbook 引用，放到 scripts/rollback/ 避免被 Flyway
-- 当作迁移扫描执行。
--
-- 场景：V019 升级后需要回退到旧定义，或 CREATE TRIGGER 失败导致保护丢失。
-- 顺序仍是「先建后删」：先把 V002 的原名触发器建回来（IF NOT EXISTS 幂等），
-- 再删  版触发器，全程至少有一条不可变保护存在 —— 零空窗。
DELIMITER $$
CREATE TRIGGER IF NOT EXISTS trg_wv_no_update BEFORE UPDATE ON workflow_version
FOR EACH ROW
BEGIN
  IF OLD.immutable = 1 AND (
       NOT (NEW.dsl_json <=> OLD.dsl_json)
       OR NEW.version_no <> OLD.version_no
       OR NEW.dsl_checksum <> OLD.dsl_checksum
       OR NEW.definition_id <> OLD.definition_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'WF-IMMUTABLE-001';
  END IF;
END$$
DELIMITER ;

DROP TRIGGER IF EXISTS trg_wv_no_update_af10;
